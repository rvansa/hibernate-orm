package org.hibernate.test.cache.infinispan.util;

import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * // TODO: Document this
 *
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public class LoggingUtil {
   public static Map<Logger, Level> traceOn() {
      Enumeration currentLoggers = LogManager.getCurrentLoggers();
      Map<Logger, Level> original = new HashMap();
      for (; currentLoggers.hasMoreElements(); ) {
         Logger logger = (Logger) currentLoggers.nextElement();
         original.put(logger, logger.getLevel());
         logger.setLevel(Level.ALL);
         try {
            Class<?> clazz = Class.forName(logger.getName());
            Field traceField = clazz.getDeclaredField("trace");
            int traceModifiers = traceField.getModifiers();
            if (Modifier.isStatic(traceModifiers)) {
               traceField.setAccessible(true);
               Field modifiersField = Field.class.getDeclaredField("modifiers");
               modifiersField.setAccessible(true);
               modifiersField.setInt(traceField, traceModifiers & ~Modifier.FINAL);
               traceField.setBoolean(null, true);
            } else {
               System.out.println("Cannot set trace on " + clazz.getName() + ".trace");
            }
         } catch (ClassNotFoundException e) {
         } catch (NoSuchFieldException e) {
         } catch (IllegalAccessException e) {
            e.printStackTrace();
         }
      }
      return original;
   }

   public static void traceOff(Map<Logger, Level> original) {
      for (Map.Entry<Logger, Level> entry : original.entrySet()) {
         entry.getKey().setLevel(entry.getValue());
      }
   }
}
