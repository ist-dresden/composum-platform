/*
 * copyright (c) 2015ff IST GmbH Dresden, Germany - https://www.ist-software.com
 *
 * This software may be modified and distributed under the terms of the MIT license.
 */
package com.composum.sling.platform.staging.search;

import com.composum.sling.core.BeanContext;
import com.composum.sling.core.filter.ResourceFilter;
import org.apache.commons.lang3.tuple.Pair;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Service for fulltext search.
 *
 * @author Hans-Peter Stoerr
 */
@Component(
        service = SearchService.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Pages Search Service"
        },
        immediate = true
)
@Designate(ocd = PlatformSearchService.SearchServiceConfiguration.class)
public class PlatformSearchService implements SearchService {

    private static final Logger LOG = getLogger(SearchService.class);

    @ObjectClassDefinition(name = "Composum Pages Search Service Configuration",
            description = "Configurations for the Composum Pages Search Service")
    public @interface SearchServiceConfiguration {

        @AttributeDefinition(name = "Overshoot", description = "Internal tuning property")
        int overshoot() default 3;

        @AttributeDefinition(name = "Maximum number of search matches", description = "Limits the number of search " +
                "matches e.g. if there is an extremely common search term entered.")
        int maximumMatchCount() default 100;

        @AttributeDefinition()
        String webconsole_configurationFactory_nameHint() default
                "{name} (maximumMatchCount: {maximumMatchCount})";
    }

    protected SearchServiceConfiguration config;

    protected List<SearchPlugin> searchPlugins =
            Collections.synchronizedList(new ArrayList<>());

    @Activate
    @Modified
    public void activate(SearchServiceConfiguration config) {
        this.config = config;
    }

    @NotNull
    @Override
    public List<Result> search(final @NotNull BeanContext context, @NotNull String selectors,
                               final @NotNull String root, final @NotNull String searchExpression,
                               final @Nullable ResourceFilter searchFilter,
                               final int offset, final Integer limit)
            throws RepositoryException, SearchTermParseException {
        List<Result> result;
        List<SearchPlugin> matchingPlugins = getMatchingPlugins(selectors);
        if (matchingPlugins.size() > 0) {
            SearchPlugin plugin = matchingPlugins.get(matchingPlugins.size() - 1);
            result = plugin.search(context, root, searchExpression, searchFilter, offset, limit);
        } else {
            LOG.error("no search plugin found for selectors '{}'", selectors);
            result = new ArrayList<>();
        }
        return result;
    }

    @Reference(
            service = SearchPlugin.class,
            policy = ReferencePolicy.DYNAMIC,
            cardinality = ReferenceCardinality.MULTIPLE
    )
    protected void addSearchPlugin(@NotNull final SearchPlugin plugin) {
        LOG.info("addSearchPlugin: {}", plugin.getClass().getSimpleName());
        plugin.setService(this);
        searchPlugins.add(plugin);
    }

    protected void removeSearchPlugin(@NotNull final SearchPlugin plugin) {
        LOG.info("removeSearchPlugin: {}", plugin.getClass().getSimpleName());
        searchPlugins.remove(plugin);
        plugin.setService(null);
    }

    /** Returns list of matching plugins sorted ascending by their rating, so that the plugin with best rating is last. */
    protected List<SearchPlugin> getMatchingPlugins(String selectors) {
        List<SearchPlugin> result = new ArrayList<>();
        for (SearchPlugin plugin : searchPlugins) {
            int rating = plugin.rating(selectors);
            if (rating > 0) {
                int index = 0;
                for (SearchPlugin p : result) {
                    if (p.rating(selectors) >= rating) {
                        break;
                    }
                    index++;
                }
                result.add(index, plugin);
            }
        }
        return result;
    }

    /**
     * Execute the query with raising limit until the required number of results is met. We don't know in advance how
     * large we have to set the limit in the query to get all neccesary results, since each page can have an a priori
     * unknown number of matches. Thus, the query is executed with an estimated limit, and is reexecuted with tripled
     * limit if the number of results is not sufficient and there are more limits.
     *
     * @return up to limit elements of the result list with the offset first elements skipped.
     */
    @NotNull
    @Override
    public List<Result> executeQueryWithRaisingLimits(LimitedQuery limitedQuery, int offset, Integer limit) {
        Pair<Boolean, List<Result>> result;
        int neededResults = Integer.MAX_VALUE;
        int currentLimit = Integer.MAX_VALUE;
        if (null != limit) {
            neededResults = offset + limit;
            currentLimit = neededResults * config.overshoot() + 5;
        }
        int lastResultCount = -1;
        do {
            result = limitedQuery.execQuery(currentLimit);
            if (currentLimit > config.maximumMatchCount()) currentLimit = config.maximumMatchCount();
            if (result.getLeft() && result.getRight().size() >= neededResults) break;
            if (result.getRight().size() <= lastResultCount) break; // panic switch; shouldn't happen
            if (currentLimit >= config.maximumMatchCount()) break;
            lastResultCount = result.getRight().size();
            currentLimit = currentLimit * 3;
            LOG.info("Reexecuting search with limit {} for {}", currentLimit, neededResults); // should rarely happen
        } while (true);
        if (result.getRight().size() <= offset) return Collections.emptyList();
        else return result.getRight().subList(offset, Math.min(result.getRight().size(), neededResults));
    }
}
