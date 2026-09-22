package com.eonmux.excalibur.test.bootstrap;

import com.eonmux.excalibur.core.memory.ForeignMemoryBootstrap;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Initializes the foreign-memory classes before the first test runs.
 *
 * <h2>What it prevents</h2>
 * <p>Two threads initializing {@code java.lang.foreign} at the same instant deadlock the JVM
 * inside the JDK's own class-initialization monitors. It takes two threads, not parallel tests:
 * storage, query, retrieval, graph and shard components all hand work to virtual threads, so a
 * single sequential test that starts a worker is already a race for the first touch.</p>
 *
 * <p>The usual safety nets do not apply. A thread waiting on a class-initialization monitor
 * cannot be interrupted, so a JUnit timeout fires against it and nothing happens; the build
 * hangs until someone kills it, with no failing test to point at.</p>
 *
 * <p>This listener runs once per test JVM, on the launcher's thread, before any test class is
 * instantiated. By the time anything else can reach FFM there is no first touch left to race
 * over.</p>
 *
 * <h2>How it is wired</h2>
 * <p>Registered through {@code META-INF/services}, and the root build puts this module on every
 * module's {@code testRuntimeOnly} classpath, so no module has to name it. It is
 * deliberately a leaf with one dependency: anything added here lands on all twenty test
 * classpaths.</p>
 *
 * <p>Gradle's test worker opens a launcher session, so the callback runs. A runner that calls
 * {@code LauncherFactory.create(...)} without opening a session - some IDE integrations do -
 * will not invoke it, and a developer reproducing a hang that way can still hang. Calling
 * {@link ForeignMemoryBootstrap#ensureInitialized()} from the test itself covers that case.</p>
 *
 * @see ForeignMemoryBootstrap
 */
public final class ForeignMemoryWarmUp implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        ForeignMemoryBootstrap.ensureInitialized();
    }
}
