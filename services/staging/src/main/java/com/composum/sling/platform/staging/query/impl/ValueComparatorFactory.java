package com.composum.sling.platform.staging.query.impl;

import org.slf4j.Logger;

import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.util.Comparator;

import static javax.jcr.PropertyType.*;
import static org.apache.commons.collections4.ComparatorUtils.*;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Compares {@link javax.jcr.Value} as JCR Query does.
 *
 * @author Hans-Peter Stoerr
 */
public class ValueComparatorFactory {

    private static final Logger LOG = getLogger(ValueComparatorFactory.class);

    public static Comparator<Value> makeComparator(boolean ascending) {
        Comparator<Value> comparator = nullLowComparator(
                rawComparator
        );
        if (ascending) return comparator;
        else return reversedComparator(comparator);
    }

    private static final Comparator nullLowNaturalComparator = nullLowComparator((Comparator) naturalComparator());

    /** Compares Values, presuming both are not null. */
    protected static final Comparator<Value> rawComparator = new Comparator<Value>() {
        @Override
        public int compare(Value o1, Value o2) {
            int comparisonType = o1.getType();
            if (o1.getType() != o2.getType()) {
                LOG.warn("Values have different types " + o1.getType() + " and " + o2.getType() + " - resorting to " +
                        "string comparison.");
                comparisonType = STRING;
            }
            return nullLowNaturalComparator.compare(retrieveValue(o1, comparisonType), retrieveValue(o2,
                    comparisonType));
        }
    };

    protected static Comparable retrieveValue(Value value, int type) {
        try {
            switch (type) {
                case DATE:
                    return value.getDate();
                case BINARY:
                    return value.getBoolean();
                case DOUBLE:
                    return value.getDouble();
                case DECIMAL:
                    return value.getDecimal();
                case LONG:
                    return value.getLong();
                case BOOLEAN:
                    return value.getBoolean();
                case STRING:
                case NAME:
                case PATH:
                case REFERENCE:
                case WEAKREFERENCE:
                case URI:
                    return value.getString();
                default: // impossible
                    throw new IllegalArgumentException("Unknown type " + type);
            }
        } catch (RepositoryException e) { // wrap since we can't throw that in a comparator
            throw new RuntimeException(e);
        }
    }

}
