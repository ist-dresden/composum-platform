/*
 * copyright (c) 2015ff IST GmbH Dresden, Germany - https://www.ist-software.com
 *
 * This software may be modified and distributed under the terms of the MIT license.
 */
package com.composum.sling.platform.staging.search;

import com.composum.sling.platform.staging.query.QueryConditionDsl.QueryCondition;
import com.composum.sling.platform.staging.query.QueryConditionDsl.QueryConditionBuilder;
import org.apache.commons.lang3.StringUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class SearchUtil {

    /**
     * @return the search expression transformed for a node name query
     */
    @NotNull
    public static String namePattern(@NotNull String searchExpression) {
        String namePattern = searchExpression.replace('*', '%');
        if (!namePattern.startsWith("%")) {
            namePattern = "%" + namePattern;
        }
        if (!namePattern.endsWith("%")) {
            namePattern = namePattern + "%";
        }
        return namePattern;
    }

    /**
     * @return the query condition for searching nodes containing a text fragment including the node name
     */
    @Nullable
    public static QueryCondition nameAndTextCondition(
            @NotNull final QueryConditionBuilder conditionBuilder, @Nullable final String searchTerm) {
        return StringUtils.isNotBlank(searchTerm)
                ? conditionBuilder.name().like().val(namePattern(searchTerm)).or().contains(searchTerm)
                : null;
    }

    /**
     * @return the query condition extended with a text search condition including the node name
     */
    @NotNull
    public static QueryCondition andNameAndTextCondition(
            @NotNull QueryCondition condition, @Nullable String searchTerm) {
        return StringUtils.isNotBlank(searchTerm)
                ? condition.and().startGroup().name().like().val(namePattern(searchTerm)).or().contains(searchTerm).endGroup()
                : condition;
    }
}
