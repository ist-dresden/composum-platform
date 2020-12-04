package com.composum.sling.platform.testing.testutil.misc;

import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.composum.sling.platform.testing.testutil.JcrTestUtils;
import org.apache.commons.beanutils.converters.CalendarConverter;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Rule;
import org.junit.Test;

import java.io.InputStream;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Some tests demonstrating how {@link org.apache.sling.api.resource.ValueMap} behaves.
 */
public class ValueMapTest {

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    public void testCalenderConversions() throws Exception {
        String path = context.uniqueRoot().content();
        Resource resource = context.build().resource(path,
                ResourceUtil.PROP_PRIMARY_TYPE, ResourceUtil.TYPE_UNSTRUCTURED).commit().getCurrentParent();
        ModifiableValueMap mvm = resource.adaptTo(ModifiableValueMap.class);
        long time = System.currentTimeMillis();
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        mvm.put("cal", calendar);
        mvm.put("date", new Date(time)); // this is actually written as binary property. Do not use!
        context.resourceResolver().commit();
        context.resourceResolver().refresh();

        ValueMap vm = context.resourceResolver().getResource(path).getValueMap();
        Object calNatural = vm.get("cal");
        long calAsLong = vm.get("cal", Long.class);
        Date calAsDate = vm.get("cal", Date.class);
        Calendar calAsCalendar = vm.get("cal", Calendar.class);
        ec.checkThat(calNatural, instanceOf(GregorianCalendar.class));
        ec.checkThat(calAsLong, is(time));
        ec.checkThat(calAsDate.getTime(), is(time));
        ec.checkThat(calAsCalendar.getTimeInMillis(), is(time));

        Object natural = vm.get("date");
        ec.checkThat(natural, instanceOf(InputStream.class)); // ouch.
        long dateAsLong = vm.get("date", Long.class); // completely broken. = 46 , no idea why. 8-}
        Date dateAsDate = vm.get("date", Date.class);
        Calendar dateAsCalendar = vm.get("date", Calendar.class);
        ec.checkThat(dateAsDate.getTime(), is(time) );
        ec.checkThat(dateAsCalendar.getTimeInMillis(), is(time) );

        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource(path));

        // one way to convert a time in milliseconds
        ec.checkThat(new CalendarConverter().convert(Calendar.class, new Date(time)).getTimeInMillis(), is(time));
    }

}
