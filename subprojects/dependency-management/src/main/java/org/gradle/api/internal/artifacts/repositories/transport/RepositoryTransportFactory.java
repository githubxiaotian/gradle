/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.repositories.transport;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.artifacts.repositories.PasswordCredentials;
import org.gradle.api.credentials.Credentials;
import org.gradle.api.internal.artifacts.ivyservice.CacheLockingManager;
import org.gradle.api.internal.file.TemporaryFileProvider;
import org.gradle.internal.resource.cached.CachedExternalResourceIndex;
import org.gradle.internal.resource.connector.ResourceConnectorFactory;
import org.gradle.internal.resource.connector.ResourceConnectorRegistrar;
import org.gradle.internal.resource.connector.ResourceConnectorSpecification;
import org.gradle.internal.resource.transfer.*;
import org.gradle.internal.resource.transport.ResourceConnectorRepositoryTransport;
import org.gradle.internal.resource.transport.file.FileTransport;
import org.gradle.internal.resource.transport.sftp.SftpClientFactory;
import org.gradle.internal.resource.transport.sftp.SftpConnectorFactory;
import org.gradle.logging.ProgressLoggerFactory;
import org.gradle.util.BuildCommencedTimeProvider;
import org.gradle.util.WrapUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RepositoryTransportFactory implements ResourceConnectorRegistrar {
    private final List<ResourceConnectorFactory> registeredProtocols = Lists.newArrayList();

    private final TemporaryFileProvider temporaryFileProvider;
    private final CachedExternalResourceIndex<String> cachedExternalResourceIndex;
    private final ProgressLoggerFactory progressLoggerFactory;
    private final BuildCommencedTimeProvider timeProvider;
    private final CacheLockingManager cacheLockingManager;

    public RepositoryTransportFactory(ProgressLoggerFactory progressLoggerFactory,
                                      TemporaryFileProvider temporaryFileProvider,
                                      CachedExternalResourceIndex<String> cachedExternalResourceIndex,
                                      BuildCommencedTimeProvider timeProvider,
                                      SftpClientFactory sftpClientFactory,
                                      CacheLockingManager cacheLockingManager) {
        this.progressLoggerFactory = progressLoggerFactory;
        this.temporaryFileProvider = temporaryFileProvider;
        this.cachedExternalResourceIndex = cachedExternalResourceIndex;
        this.timeProvider = timeProvider;
        this.cacheLockingManager = cacheLockingManager;

        register(new HttpConnectorFactory());
        register(new SftpConnectorFactory(sftpClientFactory));
        register(new S3ConnectorFactory());
    }

    @Override
    public void register(ResourceConnectorFactory resourceConnectorFactory) {
        registeredProtocols.add(resourceConnectorFactory);
    }

    public RepositoryTransport createTransport(String scheme, String name, Credentials credentials) {
        Set<String> schemes = new HashSet<String>();
        schemes.add(scheme);
        return createTransport(schemes, name, credentials);
    }

    private org.gradle.internal.resource.PasswordCredentials convertPasswordCredentials(Credentials credentials) {
        if(credentials == null) {
            return null;
        }
        if (!(credentials instanceof PasswordCredentials)) {
            throw new IllegalArgumentException(String.format("Credentials must be an instance of: %s", PasswordCredentials.class.getCanonicalName()));
        }
        PasswordCredentials passwordCredentials = (PasswordCredentials) credentials;
        return new org.gradle.internal.resource.PasswordCredentials(passwordCredentials.getUsername(), passwordCredentials.getPassword());
    }

    public RepositoryTransport createTransport(Set<String> schemes, String name, Credentials credentials) {
        validateSchemes(schemes);

        // File resources are handled slightly differently at present.
        if (WrapUtil.toSet("file").containsAll(schemes)) {
            return new FileTransport(name);
        }
        ResourceConnectorSpecification connectionDetails = new DefaultResourceConnectorSpecification(credentials);
        ExternalResourceConnector resourceConnector = findRegisteredProtocol(schemes).createResourceConnector(connectionDetails);
        return new ResourceConnectorRepositoryTransport(name, progressLoggerFactory, temporaryFileProvider, cachedExternalResourceIndex, timeProvider, cacheLockingManager, resourceConnector);
    }

    private void validateSchemes(Set<String> schemes) {
        Set<String> validSchemes = Sets.newLinkedHashSet();
        validSchemes.add("file");
        for (ResourceConnectorFactory registeredProtocol : registeredProtocols) {
            validSchemes.addAll(registeredProtocol.getSupportedProtocols());
        }
        for (String scheme : schemes) {
            if (!validSchemes.contains(scheme)) {
                throw new InvalidUserDataException(String.format("Not a supported repository protocol '%s': valid protocols are %s", scheme, validSchemes));
            }
        }
    }

    private ResourceConnectorFactory findRegisteredProtocol(Set<String> schemes) {
        for (ResourceConnectorFactory protocolRegistration : registeredProtocols) {
            if (protocolRegistration.getSupportedProtocols().containsAll(schemes)) {
                return protocolRegistration;
            }
        }
        throw new InvalidUserDataException("You cannot mix different URL schemes for a single repository. Please declare separate repositories.");
    }

    private class DefaultResourceConnectorSpecification implements ResourceConnectorSpecification {
        private final Credentials credentials;

        private DefaultResourceConnectorSpecification(Credentials credentials) {
            this.credentials = credentials;
        }

        @Override
        public <T> T getCredentials(Class<T> type) {
            if (org.gradle.internal.resource.PasswordCredentials.class.isAssignableFrom(type)) {
                return type.cast(convertPasswordCredentials(credentials));
            }
            return type.cast(credentials);
        }
    }

}
