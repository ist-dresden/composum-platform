/*
 * copyright (c) 2015ff IST GmbH Dresden, Germany - https://www.ist-software.com
 *
 * This software may be modified and distributed under the terms of the MIT license.
 */
package com.composum.platform.models.annotations;

import com.composum.sling.core.BeanContext;
import com.composum.sling.core.ResourceHandle;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A strategy to get property values, possibly internationalized.
 *
 * @author Hans-Peter Stoerr
 * @see Property
 * @since 09/2017
 */
public interface InternationalizationStrategy {

    /**
     * Retrieves the internationalized value for name (may be an attribute name or a relative path) from resource.
     *
     * @param resource    the resource for which the properties are wanted, not null
     * @param name        the name of the property - may be a relative path as well
     * @param valueClass  the class the value is to be converted into, not null
     * @param beanContext if available, the {@link BeanContext}
     * @param request     if available, the request one could get the locale from
     * @param locale      if available, the locale
     * @param parameters  the annotation parameters about the value
     * @return the value or null
     */
    <T> T getInternationalized(Resource resource, String name, Class<T> valueClass, BeanContext beanContext,
                               SlingHttpServletRequest request, Locale locale, Property parameters);

    /** No internationalization - just uses the attributes. */
    class NONE implements InternationalizationStrategy {
        @Override
        public <T> T getInternationalized(Resource resource, String name, Class<T> valueClass, BeanContext beanContext,
                                          SlingHttpServletRequest request, Locale locale, Property parameters) {
            if (parameters.inherited()) return ResourceHandle.use(resource).getInherited(name, valueClass);
            else return ResourceHandle.use(resource).getProperty(name, valueClass);
        }
    }

    /**
     * Signifies the use of a default {@link InternationalizationStrategy} - for use as "no value" in annotations. Don't
     * use this otherwise; calling it throws exceptions.
     */
    class USEDEFAULT implements InternationalizationStrategy {
        /** Throws an exception. */
        @Override
        public <T> T getInternationalized(Resource resource, String name, Class<T> valueClass, BeanContext beanContext,
                                          SlingHttpServletRequest request, Locale locale, Property parameters) {
            throw new UnsupportedOperationException("Bug: this should never be actually called.");
        }
    }

    /**
     * Default internationalization strategy used in Composum Pages: uses {@link ResourceHandle#getInherited(String,
     * Class)} to retrieve inherited values and retrieves internationalized properties from locale dependent folders
     * below i18n/ at the current resource. When a i18n-able property name is stored at a resource at path, it is looked
     * up at the following paths, in that order (the examples refer to a hypothetical locale with language=de,
     * country=DE, variant=SN):
     * <p>
     * <ol> <li>path/i18n/language_country_variant/name, for example path/i18n/de_DE_SN/name</li>
     * <li>path/i18n/language_country/name, for example path/i18n/de_DE/name</li> <li>path/i18n/language/name, for
     * example path/i18n/de/name</li> <li>path/name, for example path/name</li> </ol>
     * <p>
     * If {@link Property#inherited()} is true and none of these are found, this process is performed on parent
     * resources as well, as determined by {@link Property#inheritanceType()}.
     */
    class I18NFOLDER extends NONE implements InternationalizationStrategy {

        /** the subpath to store I18N translations of the element properties */
        public static final String I18N_PROPERTY_PATH = "i18n/";

        /**
         * {@inheritDoc} See detailed description at {@link I18NFOLDER}.
         *
         * @see I18NFOLDER
         */
        @Override
        public <T> T getInternationalized(Resource resource, String name, Class<T> valueClass, BeanContext beanContext,
                                          SlingHttpServletRequest request, Locale locale, Property parameters) {
            Locale usedLocale = getLocale(beanContext, request, locale);
            if (!parameters.i18n() || null == usedLocale)
                return super.getInternationalized(resource, name, valueClass, beanContext, request, locale, parameters);
            ResourceHandle handle = ResourceHandle.use(resource);
            List<String> i18npaths = getI18nPaths(usedLocale);
            for (String i18npath : i18npaths) {
                T value = handle.getProperty(i18npath + '/' + name, valueClass);
                if (null != value) return value;
            }
            if (parameters.inherited()) for (String i18npath : i18npaths) {
                T value = handle.getInherited(i18npath + '/' + name, valueClass, parameters.inheritanceType());
                if (null != value) return value;
            }
            return null;
        }

        protected Locale getLocale(BeanContext beanContext, SlingHttpServletRequest request, Locale locale) {
            Locale usedLocale = locale;
            if (null == locale && null != beanContext) usedLocale = beanContext.getLocale();
            if (null == locale && null != request) usedLocale = request.getLocale();
            return usedLocale;
        }

        /**
         * Calculates the paths to look at for internationalized values. For a locale de_DE_SN that would be:
         * [i18n/de_DE_SN, i18n/de_DE, i18n/de, .]
         *
         * @param locale the locale, nullable
         * @return the i18n paths, not null
         */
        public static List<String> getI18nPaths(Locale locale) {
            List<String> i18nPaths = new ArrayList<>();
            if (locale != null) {
                String variant = locale.getVariant();
                String country = locale.getCountry();
                String language = locale.getLanguage();
                if (StringUtils.isNotBlank(variant)) {
                    i18nPaths.add(I18N_PROPERTY_PATH + language + "_" + country + "_" + variant);
                }
                if (StringUtils.isNotBlank(country)) {
                    i18nPaths.add(I18N_PROPERTY_PATH + language + "_" + country);
                }
                i18nPaths.add(I18N_PROPERTY_PATH + language);
            }
            i18nPaths.add(".");
            return i18nPaths;
        }

        /**
         * @return the path to the property named by the key in the locales context
         */
        public static String getI18nPath(Locale locale, String key) {
            if (locale != null) {
                String variant = locale.getVariant();
                String country = locale.getCountry();
                String language = locale.getLanguage();
                String path = I18N_PROPERTY_PATH + language;
                if (StringUtils.isNotBlank(country)) {
                    path += "_" + country;
                }
                if (StringUtils.isNotBlank(variant)) {
                    path += "_" + variant;
                }
                return path + "/" + key;
            }
            return key;
        }
    }
}
