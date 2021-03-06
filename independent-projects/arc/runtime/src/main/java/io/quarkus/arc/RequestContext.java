/*
 * Copyright 2018 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.quarkus.arc;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.enterprise.context.ContextNotActiveException;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.context.spi.Contextual;
import javax.enterprise.context.spi.CreationalContext;

/**
 * The built-in context for {@link RequestScoped}.
 *
 * @author Martin Kouba
 */
class RequestContext implements ManagedContext {

    // It's a normal scope so there may be no more than one mapped instance per contextual type per thread
    private final ThreadLocal<Map<Contextual<?>, ContextInstanceHandle<?>>> currentContext = new ThreadLocal<>();

    @Override
    public Class<? extends Annotation> getScope() {
        return RequestScoped.class;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        Map<Contextual<?>, ContextInstanceHandle<?>> ctx = currentContext.get();
        if (ctx == null) {
            // Thread local not set - context is not active!
            throw new ContextNotActiveException();
        }
        ContextInstanceHandle<T> instance = (ContextInstanceHandle<T>) ctx.get(contextual);
        if (instance == null && creationalContext != null) {
            // Bean instance does not exist - create one if we have CreationalContext
            instance = new ContextInstanceHandleImpl<T>((InjectableBean<T>) contextual,
                    contextual.create(creationalContext), creationalContext);
            ctx.put(contextual, instance);
        }
        return instance != null ? instance.get() : null;
    }

    @Override
    public <T> T get(Contextual<T> contextual) {
        return get(contextual, null);
    }

    @Override
    public Collection<ContextInstanceHandle<?>> getAll() {
        Map<Contextual<?>, ContextInstanceHandle<?>> ctx = currentContext.get();
        if (ctx == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(ctx.values());
    }

    @Override
    public boolean isActive() {
        return currentContext.get() != null;
    }

    @Override
    public void destroy(Contextual<?> contextual) {
        Map<Contextual<?>, ContextInstanceHandle<?>> ctx = currentContext.get();
        if (ctx == null) {
            // Thread local not set - context is not active!
            throw new ContextNotActiveException();
        }
        ContextInstanceHandle<?> instance = ctx.remove(contextual);
        if (instance != null) {
            instance.destroy();
        }
    }

    @Override
    public void activate(Collection<ContextInstanceHandle<?>> initialState) {
        Map<Contextual<?>, ContextInstanceHandle<?>> state = new HashMap<>();
        if (initialState != null) {
            for (ContextInstanceHandle<?> instanceHandle : initialState) {
                if (!instanceHandle.getBean().getScope().equals(getScope())) {
                    throw new IllegalArgumentException("Invalid bean scope: " + instanceHandle.getBean());
                }
                state.put(instanceHandle.getBean(), instanceHandle);
            }
        }
        currentContext.set(state);
    }

    @Override
    public void deactivate() {
        currentContext.remove();
    }

    @Override
    public void destroy() {
        Map<Contextual<?>, ContextInstanceHandle<?>> ctx = currentContext.get();
        if (ctx != null) {
            synchronized (ctx) {
                for (InstanceHandle<?> instance : ctx.values()) {
                    try {
                        instance.destroy();
                    } catch (Exception e) {
                        throw new IllegalStateException("Unable to destroy instance" + instance.get(), e);
                    }
                }
                ctx.clear();
            }
        }
    }

}
