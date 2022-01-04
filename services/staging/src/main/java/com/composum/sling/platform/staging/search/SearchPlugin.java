/*
 * copyright (c) 2015ff IST GmbH Dresden, Germany - https://www.ist-software.com
 *
 * This software may be modified and distributed under the terms of the MIT license.
 */
package com.composum.sling.platform.staging.search;

import com.composum.sling.core.BeanContext;
import com.composum.sling.core.filter.ResourceFilter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.jcr.RepositoryException;
import java.util.List;

/**
 * the service interface to implement search strategy services for various request selectors
 */
public interface SearchPlugin {

    int rating(@NotNull String selectors);

    @NotNull
    List<SearchService.Result> search(@NotNull final BeanContext context, @NotNull final String root,
                                      @NotNull final String searchExpression, @Nullable ResourceFilter filter,
                                      final int offset, @Nullable final Integer limit)
            throws RepositoryException, SearchTermParseException;

    void setService(@Nullable SearchService service);
}
