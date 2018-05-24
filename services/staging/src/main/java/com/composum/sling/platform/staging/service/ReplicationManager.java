package com.composum.sling.platform.staging.service;

import org.apache.sling.api.resource.ResourceResolver;

import javax.jcr.RepositoryException;
import java.util.Calendar;
import java.util.List;

public interface ReplicationManager {

    String RELEASE_LABEL_PREFIX = "composum-release-";

    List<String> getReleases(ResourceResolver resolver, Iterable<String> rootPaths)
            throws RepositoryException;

    void updateToRelease(final ResourceResolver resolver, final String sitePath, String releaseLabel)
            throws RepositoryException;

    void createRelease(ResourceResolver resolver, Iterable<String> rootPaths,
                       String releaseLabel)
            throws RepositoryException;

    void removeRelease(ResourceResolver resolver, Iterable<String> rootPaths,
                       String releaseLabel, boolean deleteVersions)
            throws RepositoryException;

    void purgeReleases(ResourceResolver resolver, Iterable<String> rootPaths,
                       Calendar keepDate, int keepCount)
            throws RepositoryException;

    void rollbackToRelease(ResourceResolver resolver, Iterable<String> rootPaths,
                           String releaseLabel)
            throws RepositoryException;
}
