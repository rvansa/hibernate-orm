package org.hibernate.test.cache.infinispan.util;

import junit.framework.AssertionFailedError;
import org.jboss.logging.Logger;
import org.junit.Assert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records exceptions.
 *
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public class ExceptionHolder {
   private static final Logger log = Logger.getLogger( ExceptionHolder.class );
   private final List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
   private final List<AssertionFailedError> assertionFailures = Collections.synchronizedList(new ArrayList<>());

   public void addException(Exception e) {
      log.error("Recording exception", e);
      exceptions.add(e);
   }

   public void addAssertionFailure(AssertionFailedError e) {
      log.error("Recording failed assertion", e);
      assertionFailures.add(e);
   }

   public void checkExceptions() throws Exception {
      for (AssertionFailedError a : assertionFailures) {
         throw a;
      }
      for (Exception e : exceptions) {
         throw e;
      }
   }

   public void assertTrue(String message, boolean condition) throws Exception {
      checkExceptions();
      if (!condition) {
         Assert.fail(message);
      }
   }

   public void assertTrue(boolean condition) throws Exception {
      checkExceptions();
      if (!condition) {
         Assert.fail();
      }
   }

   public void assertFalse(String message, boolean condition) throws Exception {
      checkExceptions();
      if (condition) {
         Assert.fail(message);
      }
   }
}
