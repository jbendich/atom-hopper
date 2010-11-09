/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package com.rackspace.cloud.sense.abdera;

import com.rackspace.cloud.util.logging.ExtendedLogger;
import com.rackspace.cloud.util.logging.LoggerWrapper;
import java.util.ArrayList;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.security.auth.Subject;

import org.apache.abdera.Abdera;
import org.apache.abdera.model.Service;
import org.apache.abdera.protocol.server.CollectionAdapter;
import org.apache.abdera.protocol.server.Filter;
import org.apache.abdera.protocol.server.Provider;
import org.apache.abdera.protocol.server.ProviderHelper;
import org.apache.abdera.protocol.server.RequestContext;
import org.apache.abdera.protocol.server.RequestProcessor;
import org.apache.abdera.protocol.server.ResponseContext;
import org.apache.abdera.protocol.server.Target;
import org.apache.abdera.protocol.server.TargetType;
import org.apache.abdera.protocol.server.Transactional;
import org.apache.abdera.protocol.server.WorkspaceInfo;
import org.apache.abdera.protocol.server.WorkspaceManager;
import org.apache.abdera.protocol.server.context.ResponseContextException;
import org.apache.abdera.protocol.server.impl.SimpleSubjectResolver;
import org.apache.abdera.protocol.server.processors.CategoriesRequestProcessor;
import org.apache.abdera.protocol.server.processors.CollectionRequestProcessor;
import org.apache.abdera.protocol.server.processors.EntryRequestProcessor;
import org.apache.abdera.protocol.server.processors.MediaRequestProcessor;
import org.apache.abdera.protocol.server.processors.ServiceRequestProcessor;

public class SenseWorkspaceProvider implements Provider {

    private final static ExtendedLogger log = new LoggerWrapper("SenseWorkspaceProvider", "com.rackspace.cloud.sense");

    private final Map<TargetType, RequestProcessor> requestProcessors;
    private final SenseWorkspaceManager workspaceManager;
    private final List<Filter> filters;
    private Map<String, String> properties;
    private Abdera abdera;

    public SenseWorkspaceProvider() {
        requestProcessors = new HashMap<TargetType, RequestProcessor>();
        filters = new ArrayList<Filter>();

        // Setting default request processors:
        this.requestProcessors.put(TargetType.TYPE_SERVICE, new ServiceRequestProcessor());
        this.requestProcessors.put(TargetType.TYPE_CATEGORIES, new CategoriesRequestProcessor());
        this.requestProcessors.put(TargetType.TYPE_COLLECTION, new CollectionRequestProcessor());
        this.requestProcessors.put(TargetType.TYPE_ENTRY, new EntryRequestProcessor());
        this.requestProcessors.put(TargetType.TYPE_MEDIA, new MediaRequestProcessor());

        workspaceManager = new SenseWorkspaceManager();
    }

    public SenseWorkspaceManager getWorkspaceManager() {
        return workspaceManager;
    }

    @Override
    public void init(Abdera abdera, Map<String, String> properties) {
        this.abdera = abdera;
        this.properties = properties;
    }

    @Override
    public String getProperty(String name) {
        return properties.get(name);
    }

    @Override
    public String[] getPropertyNames() {
        return properties.keySet().toArray(new String[properties.size()]);
    }

    @Override
    public Abdera getAbdera() {
        return abdera;
    }

    @Override
    public Subject resolveSubject(RequestContext request) {
        return new SimpleSubjectResolver().resolve(request);
    }

    @Override
    public Target resolveTarget(RequestContext request) {
        return workspaceManager.resolveTarget(request);
    }

    @Override
    public String urlFor(RequestContext request, Object key, Object param) {
        return workspaceManager.urlFor(request, key, param);
    }

    @Override
    public ResponseContext process(RequestContext request) {
        final Target target = request.getTarget();

        if (target == null || target.getType() == TargetType.TYPE_NOT_FOUND) {
            return ProviderHelper.notfound(request);
        }

        final RequestProcessor processor = this.requestProcessors.get(target.getType());

        if (processor == null) {
            return ProviderHelper.notfound(request);
        }

        final WorkspaceManager wm = getWorkspaceManager();
        final CollectionAdapter adapter = wm.getCollectionAdapter(request);
        final Transactional transaction = adapter instanceof Transactional ? (Transactional) adapter : null;

        ResponseContext response = null;

        try {
            transactionStart(transaction, request);
            response = processor.process(request, wm, adapter);
            response = response != null ? response : processExtensionRequest(request, adapter);
        } catch (Throwable e) {
            if (e instanceof ResponseContextException) {
                final ResponseContextException rce = (ResponseContextException) e;

                if (rce.getStatusCode() >= 400 && rce.getStatusCode() < 500) {
                    // don't report routine 4xx HTTP errors
                    log.info(e);
                } else {
                    log.error(e);
                }
            } else {
                log.error(e);
            }

            transactionCompensate(transaction, request, e);
            response = createErrorResponse(request, e);
            return response;
        } finally {
            transactionEnd(transaction, request, response);
        }

        return response != null ? response : ProviderHelper.badrequest(request);
    }

    private void transactionCompensate(Transactional transactional, RequestContext request, Throwable e) {
        if (transactional != null) {
            transactional.compensate(request, e);
        }
    }

    private void transactionEnd(Transactional transactional, RequestContext request, ResponseContext response) {
        if (transactional != null) {
            transactional.end(request, response);
        }
    }

    private void transactionStart(Transactional transactional, RequestContext request) throws ResponseContextException {
        if (transactional != null) {
            transactional.start(request);
        }
    }

    /**
     * Subclass to customize the kind of error response to return
     */
    protected ResponseContext createErrorResponse(RequestContext request, Throwable e) {
        return ProviderHelper.servererror(request, e);
    }

    private ResponseContext processExtensionRequest(RequestContext context, CollectionAdapter adapter) {
        return adapter.extensionRequest(context);
    }

    /**
     * ?
     * 
     * @param request
     * @return
     */
    private Service getServiceElement(RequestContext request) {
        final Service service = abdera.newService();

        for (WorkspaceInfo wi : getWorkspaceManager().getWorkspaces(request)) {
            service.addWorkspace(wi.asWorkspaceElement(request));
        }
        
        return service;
    }

    public void setFilters(List<Filter> filters) {
        this.filters.clear();
        this.filters.addAll(filters);
    }

    @Override
    public Filter[] getFilters(RequestContext request) {
        return filters.toArray(new Filter[filters.size()]);
    }

    public void addFilter(Filter... filters) {
        this.filters.addAll(Arrays.asList(filters));
    }

    @Override
    public void setRequestProcessors(Map<TargetType, RequestProcessor> requestProcessors) {
        this.requestProcessors.clear();
        this.requestProcessors.putAll(requestProcessors);
    }

    @Override
    public void addRequestProcessors(Map<TargetType, RequestProcessor> requestProcessors) {
        this.requestProcessors.putAll(requestProcessors);
    }

    @Override
    public Map<TargetType, RequestProcessor> getRequestProcessors() {
        return Collections.unmodifiableMap(this.requestProcessors);
    }
}
