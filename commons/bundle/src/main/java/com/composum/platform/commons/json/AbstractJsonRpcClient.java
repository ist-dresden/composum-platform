package com.composum.platform.commons.json;

import com.composum.platform.commons.json.JsonRpcInterface.JsonRpcException;
import com.composum.platform.commons.util.ExceptionUtil;
import com.composum.sling.core.BeanContext;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonWriter;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Base class for servlets that provides in conjunction with {@link AbstractJsonRpcClient} "remote procedure calls"
 * from a Sling server to another Sling server via the usual Sling Servlets and Gson serialization of the arguments.
 * A description of the needed constraints is given at {@link JsonRpcInterface}.
 *
 * @deprecated not yet tested
 */
public abstract class AbstractJsonRpcClient<T extends JsonRpcInterface> implements InvocationHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractJsonRpcClient.class);

    protected final AtomicReference<CloseableHttpClient> httpClientRef = new AtomicReference<>();

    /** The proxy for the {@link #getInterfaceClass()}; also used as a marker of the service being active. */
    protected volatile T proxy;

    /** The {@link Gson} instanced used for serializing / deserializing things. */
    protected volatile Gson gson;

    /** Returns the interface class that is implemented by the service. */
    @Nonnull
    protected abstract Class<T> getInterfaceClass();

    /** Returns the URL to call for the service. Usually selector methodName and extension json. */
    @Nonnull
    protected abstract String makeUri(@Nonnull String methodName);

    /** A proxy whose calls are forwarded to the servlet. */
    @SuppressWarnings("unused")
    @Nonnull
    public T getProxy() {
        verifyActive();
        return proxy;
    }

    /**
     * Executes the http call to the remote system.
     *
     * @throws JsonRpcException if something went wrong with the call
     * @throws Throwable        if something else was thrown by {@link #postprocessResult(Object, StatusLine)}
     */
    @Override
    public Object invoke(Object ignored, Method method, Object[] args) throws Throwable {
        verifyActive();
        StatusLine statusLine = null;
        Object result;
        try {
            if (LOG.isDebugEnabled()) { LOG.debug("Invocation of {} with {}", method.getName(), Arrays.asList(args)); }
            HttpClientContext httpClientContext = getHttpClientContext();
            CloseableHttpClient httpClient = getHttpClient();
            HttpPut put = new HttpPut(makeUri(method.getName()));
            put.setEntity(makeEntity(method, args));

            try (CloseableHttpResponse response = httpClient.execute(put, httpClientContext)) {
                statusLine = response.getStatusLine();
                if (statusLine.getStatusCode() < 200 || statusLine.getStatusCode() > 299) {
                    throw new JsonRpcException("Not an OK status received: " + statusLine,
                            statusLine, null);
                }
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    try (InputStream content = entity.getContent()) {
                        Reader contentReader = new InputStreamReader(content, StandardCharsets.UTF_8);
                        result = getGson().fromJson(contentReader, method.getReturnType());
                        if (result == null) {
                            throw new JsonRpcException("No deserializable result " +
                                    "received. Status: " + statusLine, statusLine, null);
                        }
                    }
                } else {
                    throw new JsonRpcException("Missing entity on response to " + method.getName(),
                            statusLine, null);
                }
            }
        } catch (IOException e) {
            StringBuilder buf = new StringBuilder("Error calling ").append(method.getName());
            if (statusLine != null) {
                buf.append(", status").append(statusLine.getStatusCode()).append(" ").append(statusLine.getStatusCode());
            }
            buf.append(" : ").append(e.getMessage());
            String msg = buf.toString();
            LOG.warn(msg, e);
            throw new JsonRpcException(msg, statusLine, e);
        }
        return postprocessResult(result, statusLine);
    }

    /**
     * Creates the entity - usually a {@link JsonHttpEntity} - for the request. This writes an JSON object with the
     * parameter names as keys and the actual arguments as values.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected HttpEntity makeEntity(@Nonnull Method method, @Nonnull Object[] args) {
        Parameter[] parameters = method.getParameters();
        if (Arrays.stream(parameters).map(Parameter::getType).anyMatch(InputStream.class::isAssignableFrom)) {
            return new InputStreamEntity((InputStream) args[0]);
        }
        HttpEntity entity = new JsonHttpEntity(null, null) {
            @Override
            protected void writeTo(@Nonnull JsonWriter jsonWriter) throws IOException {
                int parameterNum = -1;
                try {
                    jsonWriter.beginObject();

                    for (parameterNum = 0; parameterNum < parameters.length; ++parameterNum) {
                        Parameter parameter = parameters[parameterNum];
                        if (BeanContext.class.equals(parameter.getType())
                                || Iterator.class.equals(parameter.getType())) {
                            // BeanContext cannot be transmitted - will be recreated on the other side.
                            // Iterator is processed later on the fly
                            continue;
                        }
                        writeParameter(args[parameterNum], jsonWriter, parameter);
                    }

                    // write iterators last since these are meant to be processed on the fly at the receiver, without
                    // being read at once into memory.
                    for (parameterNum = 0; parameterNum < parameters.length; ++parameterNum) {
                        Parameter parameter = parameters[parameterNum];
                        if (Iterator.class.equals(parameter.getType())) {
                            writeParameter(args[parameterNum], jsonWriter, parameter);
                        }
                    }

                    jsonWriter.endObject();
                } catch (IOException | RuntimeException e) {
                    LOG.warn("Error writing parameter {} of {}", parameterNum, method, e);
                    throw e;
                }
            }

        };
        return entity;
    }

    /**
     * Writes the parameterer to the jsonWriter; hook for special handling of some things.
     * This implementation handles {@link Stream}, {@link java.util.Collection}, {@link Iterator} and {@link Map}s itself.
     */
    @SuppressWarnings({"unchecked", "rawtypes", "IfStatementWithTooManyBranches"})
    protected void writeParameter(@Nullable Object arg, @Nonnull JsonWriter jsonWriter, @Nonnull Parameter parameter) throws IOException {
        if (arg == null) { return;}
        jsonWriter.name(parameter.getName());
        if (arg instanceof Iterator) {
            Iterator iterator = (Iterator) arg;
            jsonWriter.beginArray();
            while (iterator.hasNext()) {
                writeObject(iterator.next(), jsonWriter);
            }
            jsonWriter.endArray();
        } else if (arg instanceof Collection) {
            Iterable<?> collection = (Collection<?>) arg;
            jsonWriter.beginArray();
            collection.iterator().forEachRemaining(value -> writeObject(arg, jsonWriter));
            jsonWriter.endArray();
        } else if (arg instanceof Map) {
            Map<String, ?> map = (Map) arg;
            jsonWriter.beginObject();
            for (Map.Entry<String, ?> entry : map.entrySet()) {
                jsonWriter.name(entry.getKey());
                writeObject(entry.getValue(), jsonWriter);
            }
            jsonWriter.endObject();
        } else {
            writeObject(arg, jsonWriter);
        }
    }

    @SuppressWarnings("resource")
    protected void writeObject(@Nullable Object arg, @Nonnull JsonWriter jsonWriter) {
        try {
            if (arg == null) {
                jsonWriter.nullValue();
            } else {
                getGson().toJson(arg, arg.getClass(), jsonWriter);
            }
        } catch (IOException e) {
            throw ExceptionUtil.sneakyThrowException(e); // is thrown by caller - wrapping makes things worse.
        }
    }

    /**
     * Hook to perform some actions (like logging) on the result before it's returned. The result of this method is
     * actually returned, so it's usually just {result}.
     */
    @SuppressWarnings({"RedundantThrows", "unused"})
    @Nonnull
    protected Object postprocessResult(@Nonnull Object result, @Nonnull StatusLine statusLine) throws Throwable {
        return result;
    }

    /** Called to retrieve the {@link HttpClientContext}. */
    @Nonnull
    protected HttpClientContext getHttpClientContext() {
        verifyActive();
        return HttpClientContext.create();
    }

    /** Throws up if this service is not active. */
    protected void verifyActive() throws IllegalStateException {
        if (proxy == null) { throw new IllegalStateException("Service not active."); }
    }

    /**
     * Returns / creates the {@link Gson} instance to use in serializing / deserializing things. Override this
     * method if you want special settings.
     */
    @Nonnull
    protected Gson getGson() {
        if (gson == null) {
            gson = new GsonBuilder().setPrettyPrinting().create();
        }
        return gson;
    }

    @Nonnull
    protected CloseableHttpClient getHttpClient() {
        verifyActive();
        CloseableHttpClient httpClient = httpClientRef.get();
        if (httpClient == null) {
            CloseableHttpClient newHttpClient = newHttpClient();
            if (httpClientRef.compareAndSet(httpClient, newHttpClient)) {
                httpClient = httpClientRef.get();
            } else { // failure to set since it was set in parallel. Unlikely but possible.
                httpClient = httpClientRef.get();
                try {
                    newHttpClient.close();
                } catch (IOException e) {
                    LOG.error("" + e, e);
                }
                if (httpClient == null) { // very unlikely race condition
                    verifyActive();
                    throw new IllegalStateException("Weird race condition - cannot return service"); // unclear
                }
            }
        }
        return httpClient;
    }

    /**
     * Creates the httpClient - override this if you need special settings over {@link HttpClients#createDefault()}.
     * For retrieving the client is {@link #getHttpClient()}, not this method.
     */
    @Nonnull
    protected CloseableHttpClient newHttpClient() {
        return HttpClients.createDefault();
    }

    @SuppressWarnings({"EmptyTryBlock", "unchecked"})
    @Activate
    @Modified
    protected void activate() throws IOException {
        proxy = (T) Proxy.newProxyInstance(null, new Class<?>[]{getInterfaceClass()}, this);
        LOG.info("activated");
        try (CloseableHttpClient ignored = httpClientRef.getAndSet(null)) {
            // closes the httpClient since this might mean we have to change settings.
        }
    }

    @SuppressWarnings("EmptyTryBlock")
    @Deactivate
    protected void deactivate() throws IOException {
        LOG.info("deactivated");
        proxy = null;
        try (CloseableHttpClient ignored = httpClientRef.getAndSet(null)) {
            // this just closes it.
        }
    }

}
