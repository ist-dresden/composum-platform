package com.composum.sling.platform.staging.service;

public interface ReleaseMapper {

    boolean releaseMappingAllowed(String path, String uri);

    boolean releaseMappingAllowed(String path);
}
