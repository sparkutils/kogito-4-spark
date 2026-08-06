package com.sparkutils.dmn.kogito.tests.server;

import com.sparkutils.dmn.kogito.classic.*;
import com.sparkutils.dmn.kogito.common.EvalAllResultsTest;
import com.sparkutils.dmn.kogito.common.ExceptionsTest;
import org.scalatest.ConfigMap$;
import org.scalatest.Suite;
import org.scalatest.Suites;
import org.scalatest.run;
import scala.collection.JavaConverters;

import java.util.ArrayList;

/**
 * Server Test Suite
 */
public class TestSuite {

    /**
     * Execute server test suites
     */
    public static void runTests() {
        var arrayOfSuites = new ArrayList<Suite>() {{
            add(new DeepTest());
            add(new EvalAllResultsTest());
            add(new ExceptionsTest());
            add(new SimpleTest());
            add(new StaticDataTest());
        }};
        var suites = JavaConverters
                .asScalaIteratorConverter(arrayOfSuites.iterator())
                .asScala()
                .toSeq();
        run.apply(
                new Suites(suites),
                null,
                ConfigMap$.MODULE$.empty()
        );
    }

}
