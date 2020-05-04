package com.composum.platform.commons.json;

import com.composum.platform.commons.util.AutoCloseableIterator;
import com.composum.sling.core.BeanContext;
import com.composum.sling.core.servlet.Status;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Base class for servlets that provides in conjunction with {@link AbstractJsonRpcClient} "remote procedure calls"
 * from a Sling server to another Sling server via the usual Sling Servlets and Gson serialization of the arguments.
 * A description of the needed constraints is given at {@link JsonRpcInterface}.
 *
 * @deprecated not yet tested
 */
public abstract class AbstractJsonRpcServlet<T extends JsonRpcInterface> extends SlingAllMethodsServlet {

    private final Logger LOG = LoggerFactory.getLogger(getClass());

    /** Returns the interface class that is implemented by the service. */
    @Nonnull
    protected abstract Class<T> getInterfaceClass();

    /**
     * Returns the actual implementation of the service interface {@link #getInterfaceClass()} which is called on
     * receiving a request.
     */
    @Nonnull
    protected abstract T getService();

    /**
     * Creates a new {@link GsonBuilder}. Used for each request, since the request might need translation - see
     * {@link Status#initGson(GsonBuilder, Supplier)}.
     */
    protected GsonBuilder createGsonBuilder() {
        return new GsonBuilder();
    }

    /** The actual implementation, reading the request and serializing the response. */
    @SuppressWarnings("RedundantThrows")
    @Override
    protected void doPut(@Nonnull SlingHttpServletRequest request, @Nonnull SlingHttpServletResponse response)
            throws ServletException, IOException {
        Status status = null;
        try {
            Method method = getOperation(request);
            LOG.debug("Method call : {}", method);
            ArgumentProcessor processor = new ArgumentProcessor(method, request, response);
            status = processor.callMethod();
        } catch (JsonRpcInternalServletException e) {
            status = makeDefaultStatus(request, response);
            status.error(e.getMessage(), e.args);
        } catch (RuntimeException e) {
            status = makeDefaultStatus(request, response);
            status.error(e.getMessage());
        } finally {
            if (status == null) {
                status = makeDefaultStatus(request, response);
                status.error("No status created for operation {}", request.getRequestPathInfo().getSelectorString());
            }
            status.sendJson();
        }
    }

    /** Creates a status object of the default class - {@link Status}. */
    @Nonnull
    protected Status makeDefaultStatus(@Nonnull SlingHttpServletRequest request, @Nonnull SlingHttpServletResponse response) {
        return new Status(createGsonBuilder(), request, response, LoggerFactory.getLogger(getClass()));
    }

    @Nonnull
    protected Method getOperation(SlingHttpServletRequest request) throws JsonRpcInternalServletException {
        String[] selectors = request.getRequestPathInfo().getSelectors();
        String methodname = selectors[0];
        Class<T> clazz = getInterfaceClass();
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(methodname)) { return method; }
        }
        throw new JsonRpcInternalServletException("Could not find method for selectors {}",
                request.getRequestPathInfo().getSelectorString());
    }

    protected class ArgumentProcessor {

        protected final Method method;
        protected final SlingHttpServletRequest request;
        protected final SlingHttpServletResponse response;
        protected final Gson gson;
        protected final Object[] args;
        protected JsonReader jsonReader;
        protected InputStream inputStream;
        protected boolean haveOnTheFlyParameters;
        protected final Map<String, Integer> parameterNumber = new HashMap<>();
        protected final Map<String, Type> parameterType = new HashMap<>();

        public ArgumentProcessor(Method method, SlingHttpServletRequest request, SlingHttpServletResponse response) {
            this.method = method;
            this.request = request;
            this.response = response;
            this.gson = createGsonBuilder().create();
            this.args = new Object[method.getParameterCount()];
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < method.getParameterCount(); ++i) {
                Parameter parameter = parameters[i];
                parameterNumber.put(parameter.getName(), i);
                parameterType.put(parameter.getName(), parameter.getParameterizedType());
            }
        }

        /** Reads the arguments from JSON in the request, expecting them in their order in the parameter list. */
        @Nullable
        protected Status callMethod() throws IOException, JsonRpcInternalServletException {
            OnTheFlyParameter<?> lastOnTheFlyParameter = null;
            try {
                boolean readMoreArguments = true;
                for (Parameter parameter : method.getParameters()) {
                    LOG.debug("Reading parameter {}", parameter.getName());
                    if (BeanContext.class.equals(parameter.getType())) {
                        args[parameterNumber.get(parameter.getName())] = new BeanContext.Service(request, response);
                    } else if (InputStream.class.equals(parameter.getType())) {
                        args[parameterNumber.get(parameter.getName())] = getInputStream();
                        readMoreArguments = false;
                    }
                }
                if (readMoreArguments) {
                    while (readMoreArguments && getJsonReader().hasNext()) {
                        String parameterName = getJsonReader().nextName();
                        Type type = parameterType.get(parameterName);
                        if (type == null) {
                            throw new IllegalArgumentException("Unknown parameter " + parameterName);
                        } else if (type instanceof Iterator) {
                            // all iterators must come last for on the fly parsing, and are read on demand only.
                            haveOnTheFlyParameters = true;
                            for (Parameter parameter : method.getParameters()) {
                                // only read the name if it wasn't parameterName, which has already been read.
                                String iteratorName = parameterName.equals(parameter.getName()) ? null : parameter.getName();
                                JsonArrayAsIterable<?> iterable =
                                        new JsonArrayAsIterable<>(getJsonReader(), parameter.getType(), gson, iteratorName);
                                //noinspection unchecked,ObjectAllocationInLoop,rawtypes
                                lastOnTheFlyParameter = new OnTheFlyParameter(lastOnTheFlyParameter, iterable);
                                args[parameterNumber.get(parameter.getName())] = lastOnTheFlyParameter;
                                readMoreArguments = false;
                            }
                        } else {
                            Object arg = gson.fromJson(jsonReader, type);
                            args[parameterNumber.get(parameterName)] = arg;
                        }
                    }
                }
                if (jsonReader != null && !haveOnTheFlyParameters) {
                    jsonReader.endObject();
                }
                return invokeMethod();
            } finally {
                if (jsonReader != null) {
                    if (haveOnTheFlyParameters) { // read it until the end to do a syntax check
                        if (lastOnTheFlyParameter != null) {
                            lastOnTheFlyParameter.close();
                        }
                        jsonReader.endObject();
                    }
                    jsonReader.close();
                }
                if (inputStream != null) { inputStream.close(); }
            }
        }

        @Nonnull
        protected InputStream getInputStream() throws JsonRpcInternalServletException, IOException {
            if (null != jsonReader) {
                throw new JsonRpcInternalServletException("Both an InputStream parameter and Json-Parameter present.");
            }
            inputStream = request.getInputStream();
            return inputStream;
        }

        @Nonnull
        protected JsonReader getJsonReader() throws JsonRpcInternalServletException, IOException {
            if (null != inputStream) {
                throw new JsonRpcInternalServletException("Both an InputStream parameter and Json-Parameter present.");
            }
            jsonReader = new JsonReader(request.getReader());
            jsonReader.beginObject();
            return jsonReader;
        }

        @Nullable
        protected Status invokeMethod() throws JsonRpcInternalServletException {
            try {
                return (Status) method.invoke(null, args);
            } catch (IllegalAccessException e) {
                LOG.error("On " + method, e);
                throw new JsonRpcInternalServletException("Illegal access to method", (Object[]) null);
            } catch (InvocationTargetException e) { // FIXME(hps,03.02.20) possible / sensible to serialize this?
                throw new JsonRpcInternalServletException("Method threw exception", e);
            }
        }

    }

    /** Internal exception that leads to abort. */
    protected static class JsonRpcInternalServletException extends Exception {

        protected final Object[] args;

        /** The message to put into the status. */
        public JsonRpcInternalServletException(String message, Object... args) {
            super(message);
            this.args = args != null ? args : new Object[0];
        }
    }

    /**
     * Creates an {@link JsonArrayAsIterable} on first access of the iterator, and makes sure previous iterators are
     * closed at this point.
     */
    protected static class OnTheFlyParameter<T> implements AutoCloseableIterator<T> {

        protected final OnTheFlyParameter<?> lastOnTheFlyParameter;
        protected final JsonArrayAsIterable<T> iterable;
        protected AutoCloseableIterator<T> iterator;

        public OnTheFlyParameter(OnTheFlyParameter<?> lastOnTheFlyParameter, JsonArrayAsIterable<T> iterable) {
            this.lastOnTheFlyParameter = lastOnTheFlyParameter;
            this.iterable = iterable;
        }

        /** Makes sure the data of the last parameter was read. */
        protected void closeLastParameter() {
            if (lastOnTheFlyParameter != null) { lastOnTheFlyParameter.close(); }
        }

        @Override
        public void close() {
            closeLastParameter();
            iterable.skipAndClose();
        }

        @Nonnull
        protected AutoCloseableIterator<T> getWrappedIterator() {
            closeLastParameter();
            if (iterator == null) {
                iterator = iterable.iterator();
            }
            return iterator;
        }

        @Override
        public boolean hasNext() {
            return getWrappedIterator().hasNext();
        }

        @Nullable
        @Override
        public T next() {
            return getWrappedIterator().next();
        }
    }
}
