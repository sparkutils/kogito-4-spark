package com.sparkutils.dmn.kogito.tests.client;

import com.sparkutils.dmn.kogito.common.*;
import org.scalatest.ConfigMap$;
import org.scalatest.Suite;
import org.scalatest.Suites;
import org.scalatest.run;
import scala.collection.JavaConverters;

import java.util.ArrayList;

/**
 * Client Test Suite
 */
public class TestSuite {

    /**
     * Execute client test suites
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
