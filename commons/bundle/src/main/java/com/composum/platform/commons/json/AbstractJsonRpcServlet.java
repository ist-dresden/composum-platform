package com.composum.platform.commons.json;

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
import javax.servlet.ServletException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Base class for servlets that provides in conjunction with {@link AbstractJsonRpcClient} "remote procedure calls"
 * from a Sling server to another Sling server via the usual Sling Servlets and Gson serialization of the arguments.
 * A description of the needed constraints is given at {@link JsonRpcInterface}.
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
    @Override
    protected void doPut(@Nonnull SlingHttpServletRequest request, @Nonnull SlingHttpServletResponse response)
            throws ServletException, IOException {
        Status status = null;
        try {
            Method method = getOperation(request);
            LOG.debug("Method call : {}", method);
            status = callMethod(method, request, response);
        } catch (JsonRpcInternalServletException e) {
            status = makeDefaultStatus(request, response);
            status.error(e.getMessage(), e.args);
        } finally {
            if (status == null) {
                status = makeDefaultStatus(request, response);
                status.error("No status created for operation {}", request.getRequestPathInfo().getSelectorString());
            }
            status.sendJson();
        }
    }

    /** Reads the arguments from JSON in the request, expecting them in their order in the parameter list. */
    protected Status callMethod(Method method, SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException, JsonRpcInternalServletException {
        Gson gson = createGsonBuilder().create();
        List<Object> args = new ArrayList<>();
        try (JsonReader jsonReader = new JsonReader(request.getReader())) {
            jsonReader.beginObject();
            for (Parameter parameter : method.getParameters()) {
                LOG.debug("Reading parameter {}", parameter.getName());
                if (BeanContext.class.isAssignableFrom(parameter.getType())) {
                    args.add(new BeanContext.Service(request, response));
                } else {
                    String nextName = jsonReader.nextName();
                    if (!nextName.equals(parameter.getName())) {
                        throw new JsonRpcInternalServletException("Expected parameter {} but got {}", parameter.getName(), nextName);
                    }
                    Object arg = gson.fromJson(jsonReader, parameter.getParameterizedType());
                    args.add(arg);
                }
                // FIXME(hps,03.02.20) What about streaming parameters? Possible idea: if the last parameters are
                // Iterator<whatnot> they are delayed - only read on first use.
            }
            jsonReader.endObject();
        }
        try {
            return (Status) method.invoke(null, args.toArray(new Object[0]));
        } catch (IllegalAccessException e) {
            LOG.error("On " + method, e);
            throw new JsonRpcInternalServletException("Illegal access to method", (Object[]) null);
        } catch (InvocationTargetException e) { // FIXME(hps,03.02.20) possible / sensible to serialize this?
            throw new JsonRpcInternalServletException("Method threw exception", e);
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

    /** Internal exception that leads to abort. */
    protected static class JsonRpcInternalServletException extends Exception {

        protected final Object[] args;

        /** The message to put into the status. */
        public JsonRpcInternalServletException(String message, Object... args) {
            super(message);
            this.args = args != null ? args : new Object[0];
        }
    }
}
