package ru.tarkovskiy.api.base;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import ru.tarkovskiy.api.utils.TestDataCleanup;

public abstract class BaseApiTest {

    protected TestDataCleanup cleanup;

    @BeforeMethod
    public void setUp() {
        cleanup = new TestDataCleanup();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        if (cleanup != null) {
            cleanup.cleanup();
        }
    }
}