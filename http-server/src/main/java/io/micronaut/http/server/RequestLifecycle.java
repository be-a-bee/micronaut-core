/*
 * Copyright 2017-2022 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.server;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.core.execution.ExecutionFlow;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.core.type.ReturnType;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.filter.FilterRunner;
import io.micronaut.http.filter.GenericHttpFilter;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import io.micronaut.http.server.types.files.FileCustomizableResponseType;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.json.JsonSyntaxException;
import io.micronaut.web.router.DefaultRouteInfo;
import io.micronaut.web.router.RouteInfo;
import io.micronaut.web.router.RouteMatch;
import io.micronaut.web.router.UriRouteMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * This class handles the full route processing lifecycle for a request.
 *
 * @author Jonas Konrad
 * @since 4.0.0
 */
@Internal
public class RequestLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(RequestLifecycle.class);

    private final RouteExecutor routeExecutor;
    private boolean multipartEnabled = true;

    /**
     * @param routeExecutor The route executor to use for route resolution
     */
    protected RequestLifecycle(RouteExecutor routeExecutor) {
        this.routeExecutor = Objects.requireNonNull(routeExecutor, "routeExecutor");
    }

    protected final void multipartEnabled(boolean multipartEnabled) {
        this.multipartEnabled = multipartEnabled;
    }

    /**
     * Execute this request normally.
     *
     * @param request The request
     * @return The response to the request.
     */
    protected final ExecutionFlow<HttpResponse<?>> normalFlow(HttpRequest<?> request) {
        Objects.requireNonNull(request, "request");
        if (!multipartEnabled) {
            MediaType contentType = request.getContentType().orElse(null);
            if (contentType != null &&
                contentType.equals(MediaType.MULTIPART_FORM_DATA_TYPE)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Multipart uploads have been disabled via configuration. Rejected request for URI {}, method {}, and content type {}", request.getUri(),
                        request.getMethodName(), contentType);
                }
                return onStatusError(
                    request,
                    HttpResponse.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
                    "Content Type [" + contentType + "] not allowed"
                );
            }
        }

        UriRouteMatch<Object, Object> routeMatch = routeExecutor.findRouteMatch(request);
        if (routeMatch == null) {
            //Check if there is a file for the route before returning route not found
            FileCustomizableResponseType fileCustomizableResponseType = findFile(request);
            if (fileCustomizableResponseType != null) {
                return runWithFilters(request, (filteredRequest, propagatedContext)
                    -> ExecutionFlow.just(HttpResponse.ok(fileCustomizableResponseType)));
            }
            return onRouteMiss(request);
        }

        RouteExecutor.setRouteAttributes(request, routeMatch);

        if (LOG.isTraceEnabled()) {
            LOG.trace("Matched route {} - {} to controller {}", request.getMethodName(), request.getUri().getPath(), routeMatch.getDeclaringType());
        }
        // all ok proceed to try and execute the route
        if (routeMatch.getRouteInfo().isWebSocketRoute()) {
            return onStatusError(
                request,
                HttpResponse.status(HttpStatus.BAD_REQUEST),
                "Not a WebSocket request");
        }

        return runWithFilters(request, (filteredRequest, propagatedContext) -> executeRoute(filteredRequest, propagatedContext, routeMatch));
    }

    private ExecutionFlow<HttpResponse<?>> executeRoute(HttpRequest<?> request,
                                                        PropagatedContext propagatedContext,
                                                        RouteMatch<Object> routeMatch) {
        ExecutionFlow<RouteMatch<?>> routeMatchFlow = fulfillArguments(routeMatch, request);
        ExecutionFlow<HttpResponse<?>> responseFlow = callRoute(routeMatchFlow, request, propagatedContext);
        responseFlow = handleStatusException(responseFlow, request, routeMatch, propagatedContext);
        return onErrorNoFilter(responseFlow, request, propagatedContext);
    }

    private ExecutionFlow<HttpResponse<?>> callRoute(ExecutionFlow<RouteMatch<?>> flux,
                                                     HttpRequest<?> filteredRequest,
                                                     PropagatedContext propagatedContext) {
        RouteMatch<?> routeMatch = flux.tryCompleteValue();
        if (routeMatch != null) {
            return routeExecutor.callRoute(propagatedContext, routeMatch, filteredRequest);
        }
        return flux.flatMap(rm -> routeExecutor.callRoute(propagatedContext, rm, filteredRequest));
    }

    private ExecutionFlow<HttpResponse<?>> handleStatusException(ExecutionFlow<HttpResponse<?>> flux,
                                                                 HttpRequest<?> request,
                                                                 RouteMatch<?> routeMatch,
                                                                 PropagatedContext propagatedContext) {
        HttpResponse<?> response = flux.tryCompleteValue();
        if (response != null) {
            return handleStatusException(request, response, routeMatch, propagatedContext);
        }
        return flux.flatMap(res -> handleStatusException(request, res, routeMatch, propagatedContext));
    }

    private ExecutionFlow<HttpResponse<?>> onErrorNoFilter(ExecutionFlow<HttpResponse<?>> flux,
                                                           HttpRequest<?> request,
                                                           PropagatedContext propagatedContext) {
        if (flux.tryCompleteValue() != null) {
            return flux;
        }
        Throwable throwable = flux.tryCompleteError();
        if (throwable != null) {
            return onErrorNoFilter(request, throwable, propagatedContext);
        }
        return flux.onErrorResume(exp -> onErrorNoFilter(request, exp, propagatedContext));
    }

    /**
     * Handle an error in this request. Also runs filters for the error handling.
     *
     * @param request   The request
     * @param throwable The error
     * @return The response for the error
     */
    protected final ExecutionFlow<HttpResponse<?>> onError(HttpRequest<?> request, Throwable throwable) {
        return runWithFilters(request, (filteredRequest, propagatedContext) -> onErrorNoFilter(filteredRequest, throwable, propagatedContext));
    }

    private ExecutionFlow<HttpResponse<?>> onErrorNoFilter(HttpRequest<?> request, Throwable t, PropagatedContext propagatedContext) {
        // find the origination of the route
        Optional<RouteInfo> previousRequestRouteInfo = request.getAttribute(HttpAttributes.ROUTE_INFO, RouteInfo.class);
        Class<?> declaringType = previousRequestRouteInfo.map(RouteInfo::getDeclaringType).orElse(null);

        if ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            // top level exceptions returned by CompletableFutures. These always wrap the real exception thrown.
            t = t.getCause();
        }
        if (t instanceof ConversionErrorException cee && cee.getCause() instanceof JsonSyntaxException jse) {
            // with delayed parsing, json syntax errors show up as conversion errors
            t = jse;
        }
        final Throwable cause = t;

        RouteMatch<?> errorRoute = routeExecutor.findErrorRoute(cause, declaringType, request);
        if (errorRoute != null) {
            if (routeExecutor.serverConfiguration.isLogHandledExceptions()) {
                routeExecutor.logException(cause);
            }
            try {
                return ExecutionFlow.just(errorRoute)
                    .flatMap(routeMatch -> routeExecutor.callRoute(propagatedContext, routeMatch, request)
                        .flatMap(res -> handleStatusException(request, res, routeMatch, propagatedContext))
                    )
                    .onErrorResume(u -> createDefaultErrorResponseFlow(request, u))
                    .<HttpResponse<?>>map(response -> {
                        response.setAttribute(HttpAttributes.EXCEPTION, cause);
                        return response;
                    })
                    .onErrorResume(throwable -> createDefaultErrorResponseFlow(request, throwable));
            } catch (Throwable e) {
                return createDefaultErrorResponseFlow(request, e);
            }
        } else {
            Optional<BeanDefinition<ExceptionHandler>> optionalDefinition = routeExecutor.beanContext.findBeanDefinition(ExceptionHandler.class, Qualifiers.byTypeArgumentsClosest(cause.getClass(), Object.class));
            if (optionalDefinition.isPresent()) {
                BeanDefinition<ExceptionHandler> handlerDefinition = optionalDefinition.get();
                final Optional<ExecutableMethod<ExceptionHandler, Object>> optionalMethod = handlerDefinition.findPossibleMethods("handle").findFirst();
                RouteInfo<Object> routeInfo;
                if (optionalMethod.isPresent()) {
                    routeInfo = new ExecutableRouteInfo<>(optionalMethod.get(), true);
                } else {
                    routeInfo = new DefaultRouteInfo<>(
                        AnnotationMetadata.EMPTY_METADATA,
                        ReturnType.of(Object.class),
                        List.of(),
                        MediaType.fromType(handlerDefinition.getBeanType()).map(Collections::singletonList).orElse(Collections.emptyList()),
                        handlerDefinition.getBeanType(),
                        true,
                        false,
                        MessageBodyHandlerRegistry.EMPTY
                    );
                }
                Supplier<ExecutionFlow<HttpResponse<?>>> responseSupplier = () -> {
                    ExceptionHandler<Throwable, ?> handler = routeExecutor.beanContext.getBean(handlerDefinition);
                    try {
                        if (routeExecutor.serverConfiguration.isLogHandledExceptions()) {
                            routeExecutor.logException(cause);
                        }
                        Object result = handler.handle(request, cause);
                        return routeExecutor.createResponseForBody(propagatedContext, request, result, routeInfo, null);
                    } catch (Throwable e) {
                        return createDefaultErrorResponseFlow(request, e);
                    }
                };
                ExecutionFlow<HttpResponse<?>> responseFlow;
                final ExecutorService executor = routeExecutor.findExecutor(routeInfo);
                if (executor != null) {
                    responseFlow = ExecutionFlow.async(executor, responseSupplier);
                } else {
                    responseFlow = responseSupplier.get();
                }
                return responseFlow
                    .<HttpResponse<?>>map(response -> {
                        response.setAttribute(HttpAttributes.EXCEPTION, cause);
                        return response;
                    })
                    .onErrorResume(throwable -> createDefaultErrorResponseFlow(request, throwable));
            }
            if (RouteExecutor.isIgnorable(cause)) {
                RouteExecutor.logIgnoredException(cause);
                return ExecutionFlow.empty();
            }
            return createDefaultErrorResponseFlow(request, cause);
        }
    }

    /**
     * Run the filters for this request, and then run the given flow.
     *
     * @param request   The request
     * @param responseProvider Downstream flow, runs inside the filters
     * @return Execution flow that completes after the all the filters and the downstream flow
     */
    protected final ExecutionFlow<HttpResponse<?>> runWithFilters(HttpRequest<?> request, BiFunction<HttpRequest<?>, PropagatedContext, ExecutionFlow<HttpResponse<?>>> responseProvider) {
        List<GenericHttpFilter> httpFilters = routeExecutor.router.findFilters(request);
        FilterRunner filterRunner = new FilterRunner(httpFilters, responseProvider) {
            @Override
            protected ExecutionFlow<HttpResponse<?>> processResponse(HttpRequest<?> request, HttpResponse<?> response, PropagatedContext propagatedContext) {
                RouteInfo<?> routeInfo = response.getAttribute(HttpAttributes.ROUTE_INFO, RouteInfo.class).orElse(null);
                return handleStatusException(request, response, routeInfo, propagatedContext)
                    .onErrorResume(throwable -> onErrorNoFilter(request, throwable, propagatedContext));
            }

            @Override
            protected ExecutionFlow<HttpResponse<?>> processFailure(HttpRequest<?> request, Throwable failure, PropagatedContext propagatedContext) {
                return onErrorNoFilter(request, failure, propagatedContext);
            }
        };
        return filterRunner.run(request);
    }

    private ExecutionFlow<HttpResponse<?>> handleStatusException(HttpRequest<?> request,
                                                                 HttpResponse<?> response,
                                                                 @Nullable
                                                                 RouteMatch<?> routeMatch,
                                                                 PropagatedContext propagatedContext) {
        if (response.code() < 400) {
            return ExecutionFlow.just(response);
        }
        RouteInfo<?> routeInfo = routeMatch == null ? null : routeMatch.getRouteInfo();
        return handleStatusException(request, response, routeInfo, propagatedContext);
    }

    private ExecutionFlow<HttpResponse<?>> handleStatusException(HttpRequest<?> request,
                                                                 HttpResponse<?> response,
                                                                 RouteInfo<?> routeInfo,
                                                                 PropagatedContext propagatedContext) {
        if (response.code() >= 400 && routeInfo != null && !routeInfo.isErrorRoute()) {
            RouteMatch<Object> statusRoute = routeExecutor.findStatusRoute(request, response.code(), routeInfo);
            if (statusRoute != null) {
                return executeRoute(request, propagatedContext, statusRoute);
            }
        }
        return ExecutionFlow.just(response);
    }

    private ExecutionFlow<HttpResponse<?>> createDefaultErrorResponseFlow(HttpRequest<?> httpRequest, Throwable cause) {
        return ExecutionFlow.just(routeExecutor.createDefaultErrorResponse(httpRequest, cause));
    }

    final ExecutionFlow<HttpResponse<?>> onRouteMiss(HttpRequest<?> httpRequest) {
        HttpMethod httpMethod = httpRequest.getMethod();
        String requestMethodName = httpRequest.getMethodName();
        MediaType contentType = httpRequest.getContentType().orElse(null);

        if (LOG.isDebugEnabled()) {
            LOG.debug("No matching route: {} {}", httpMethod, httpRequest.getUri());
        }

        // if there is no route present try to locate a route that matches a different HTTP method
        final List<UriRouteMatch<Object, Object>> anyMatchingRoutes = routeExecutor.router.findAny(httpRequest);
        final Collection<MediaType> acceptedTypes = httpRequest.accept();
        final boolean hasAcceptHeader = CollectionUtils.isNotEmpty(acceptedTypes);

        Set<MediaType> acceptableContentTypes = contentType != null ? new HashSet<>(5) : null;
        Set<String> allowedMethods = new HashSet<>(5);
        Set<MediaType> produceableContentTypes = hasAcceptHeader ? new HashSet<>(5) : null;
        for (UriRouteMatch<?, ?> anyRoute : anyMatchingRoutes) {
            final String routeMethod = anyRoute.getRouteInfo().getHttpMethodName();
            if (!requestMethodName.equals(routeMethod)) {
                allowedMethods.add(routeMethod);
            }
            if (contentType != null && !anyRoute.getRouteInfo().doesConsume(contentType)) {
                acceptableContentTypes.addAll(anyRoute.getRouteInfo().getConsumes());
            }
            if (hasAcceptHeader && !anyRoute.getRouteInfo().doesProduce(acceptedTypes)) {
                produceableContentTypes.addAll(anyRoute.getRouteInfo().getProduces());
            }
        }

        if (CollectionUtils.isNotEmpty(acceptableContentTypes)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Content type not allowed for URI {}, method {}, and content type {}", httpRequest.getUri(),
                    requestMethodName, contentType);
            }
            return onStatusError(
                httpRequest,
                HttpResponse.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
                "Content Type [" + contentType + "] not allowed. Allowed types: " + acceptableContentTypes);
        }
        if (CollectionUtils.isNotEmpty(produceableContentTypes)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Content type not allowed for URI {}, method {}, and content type {}", httpRequest.getUri(),
                    requestMethodName, contentType);
            }
            return onStatusError(
                httpRequest,
                HttpResponse.status(HttpStatus.NOT_ACCEPTABLE),
                "Specified Accept Types " + acceptedTypes + " not supported. Supported types: " + produceableContentTypes);
        }
        if (!allowedMethods.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Method not allowed for URI {} and method {}", httpRequest.getUri(), requestMethodName);
            }
            return onStatusError(
                httpRequest,
                HttpResponse.notAllowedGeneric(allowedMethods),
                "Method [" + requestMethodName + "] not allowed for URI [" + httpRequest.getUri() + "]. Allowed methods: " + allowedMethods);
        }
        return onStatusError(
            httpRequest,
            HttpResponse.status(HttpStatus.NOT_FOUND),
            "Page Not Found");
    }

    /**
     * Build a status response. Calls any status routes, if available.
     *
     * @param request         The request
     * @param defaultResponse The default response if there is no status route
     * @param message         The error message
     * @return The computed response flow
     */
    protected final ExecutionFlow<HttpResponse<?>> onStatusError(HttpRequest<?> request, MutableHttpResponse<?> defaultResponse, String message) {
        Optional<RouteMatch<Object>> statusRoute = routeExecutor.router.findStatusRoute(defaultResponse.status(), request);
        if (statusRoute.isPresent()) {
            return runWithFilters(request, (filteredRequest, propagatedContext)
                -> executeRoute(filteredRequest, propagatedContext, statusRoute.get()));
        }
        if (request.getMethod() != HttpMethod.HEAD) {
            defaultResponse = routeExecutor.errorResponseProcessor.processResponse(ErrorContext.builder(request)
                .errorMessage(message)
                .build(), defaultResponse);
            if (defaultResponse.getContentType().isEmpty()) {
                defaultResponse = defaultResponse.contentType(MediaType.APPLICATION_JSON_TYPE);
            }
        }
        MutableHttpResponse<?> finalDefaultResponse = defaultResponse;
        return runWithFilters(request, (filteredRequest, propagatedContext) -> ExecutionFlow.just(finalDefaultResponse));
    }

    /**
     * Try to find a static file for this request. If there is a file, filters will still run, but
     * only after the call to this method.
     *
     * @param request The request
     * @return The file at this path, or {@code null} if none is found
     */
    @Nullable
    protected FileCustomizableResponseType findFile(HttpRequest<?> request) {
        return null;
    }

    /**
     * Fulfill the arguments of the given route with data from the request. If necessary, this also
     * waits for body data to be available, if there are arguments that need immediate binding.<br>
     * Note that in some cases some arguments may still be unsatisfied after this, if they are
     * missing and are {@link Optional}. They are satisfied with {@link Optional#empty()} later.
     *
     * @param routeMatch The route match to fulfill
     * @param request The request
     * @return The fulfilled route match, after all necessary data is available
     */
    protected ExecutionFlow<RouteMatch<?>> fulfillArguments(RouteMatch<?> routeMatch, HttpRequest<?> request) {
        // try to fulfill the argument requirements of the route
        routeExecutor.requestArgumentSatisfier.fulfillArgumentRequirementsBeforeFilters(routeMatch, request);
        return ExecutionFlow.just(routeMatch);
    }
}
