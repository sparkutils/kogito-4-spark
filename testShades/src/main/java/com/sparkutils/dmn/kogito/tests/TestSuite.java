package com.sparkutils.dmn.kogito.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.internal.TextListener;
import com.sparkutils.dmn.kogito.DeepTest;
import com.sparkutils.dmn.kogito.EvalAllResultsTest;
import com.sparkutils.dmn.kogito.SimpleTest;
import com.sparkutils.dmn.kogito.ExceptionsTest;
import com.sparkutils.dmn.kogito.ContextTest;

/**
 * The test suite
 */
public class TestSuite {
    public static void runTests() {

        JUnitCore junit = new JUnitCore();
        junit.addListener(new TextListener(System.out));

        Result result = junit.run(
                DeepTest.class,
                EvalAllResultsTest.class,
                SimpleTest.class,
                ExceptionsTest.class,
                ContextTest.class
        );

        resultReport(result);
    }

    public static void resultReport(Result result) {
        System.out.println("Finished. Result: Failures: " +
                result.getFailureCount() + ". Ignored: " +
                result.getIgnoreCount() + ". Tests run: " +
                result.getRunCount() + ". Time: " +
                result.getRunTime() + "ms.");
    }

    /**
     * Allow for running individual tests from DBRs using a higher scalatest
     * @param cname
     */
    public static void runClass(String cname) throws java.lang.ClassNotFoundException {
        JUnitCore junit = new JUnitCore();
        junit.addListener(new TextListener(System.out));

        Result result = junit.run( TestSuite.class.getClassLoader().loadClass(cname) );
        resultReport(result);
    }
}
