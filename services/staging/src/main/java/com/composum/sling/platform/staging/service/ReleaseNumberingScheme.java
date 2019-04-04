package com.composum.sling.platform.staging.service;

import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.Math.min;

/**
 * Strategy to create the key for a release. The normal scheme is to use an 'r' with a series of numbers separated by dots,
 * e.g. <code>r2.4.5</code> and increase the first, second or third number (discarding the later numbers),
 * depending on whether you want a new major, minor or bugfix release.
 */
public interface ReleaseNumberingScheme {

    /** Creates a new release key from the last one - e.g. r1.6.0 from r1.5.3 . */
    @Nonnull
    String bumpRelease(@Nonnull String oldname);

    /** New major release - increases the first version number , e.g. r1.5.3 or r1 to r2 . */
    ReleaseNumberingScheme MAJOR = new DefaultReleaseNumberingScheme(0);

    /** New minor release - increases the second version number , e.g. r1.5.3 or r1.5 to r1.6 . */
    ReleaseNumberingScheme MINOR = new DefaultReleaseNumberingScheme(1);

    /** New minor release - increases the second version number , e.g. r1.5.3 or r1.5 to r1.5.4 , r3 to r3.0.1. */
    ReleaseNumberingScheme BUGFIX = new DefaultReleaseNumberingScheme(2);

    /**
     * Comparator ordering the numbers according to the {@link DefaultReleaseNumberingScheme} so that the numerical parts
     * are compared numerically.
     */
    Comparator<String> COMPARATOR_RELEASES = new Comparator<String>() {

        final String DIGITNONDIGIT_BOUNDARY = "(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)";

        @Override
        public int compare(@Nonnull String r1, @Nonnull String r2) {
            String[] parts1 = r1.split(DIGITNONDIGIT_BOUNDARY);
            String[] parts2 = r2.split(DIGITNONDIGIT_BOUNDARY);
            for (int i = 0; i < min(parts1.length, parts2.length); ++i) {
                String p1 = parts1[i];
                String p2 = parts2[i];
                int cmp;
                if (p1.matches("\\d+") && p2.matches("\\d+")) {
                    cmp = Comparator.<Integer>naturalOrder().compare(Integer.valueOf(p1), Integer.valueOf(p2));
                } else cmp = Comparator.<String>naturalOrder().compare(p1, p2);
                if (cmp != 0) return cmp;
            }
            return Comparator.<Integer>naturalOrder().compare(parts1.length, parts2.length);
        }
    };

    /**
     * Implements a default numbering scheme where a release key is the letter 'r' followed by one to 3 dot-separated numbers.
     * We increase the required position (0 for major, 1 for minor, 2 for bugfix) and discard all higher positions. If there
     * are missing numbers, these are set to 0 - e.g. a bugfix increment on r1 goes to r1.0.1 .
     */
    class DefaultReleaseNumberingScheme implements ReleaseNumberingScheme {
        private final int increasePosition;

        public DefaultReleaseNumberingScheme(int increasePosition) {
            if (increasePosition < 0)
                throw new IllegalArgumentException("Illegal argument: " + increasePosition);
            this.increasePosition = increasePosition;
        }

        @Nonnull
        @Override
        public String bumpRelease(@Nonnull String oldname) {
            if (StringUtils.isBlank(oldname)) return "r0";
            String[] numbers = oldname.split("\\D+");
            List<Integer> rnum = Arrays.asList(numbers).stream()
                    .filter(StringUtils::isNotBlank)
                    .map(Integer::valueOf)
                    .collect(Collectors.toList());
            if (increasePosition < rnum.size()) rnum = rnum.subList(0, increasePosition + 1);
            for (int i = rnum.size(); i < increasePosition + 1; ++i) rnum.add(0);
            rnum.set(increasePosition, rnum.get(increasePosition) + 1);
            return "r" + rnum.stream().map(String::valueOf).collect(Collectors.joining("."));
        }
    }
}
