package com.eonmux.excalibur.api.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HttpMethod enum.
 */
class HttpMethodTest {

    @Test
    @DisplayName("GET method should identify correctly")
    void testGetMethod() {
        HttpMethod method = HttpMethod.GET;

        assertTrue(method.isGet());
        assertFalse(method.isPost());
        assertFalse(method.isPut());
        assertFalse(method.isDelete());
        assertFalse(method.isPatch());
        assertTrue(method.isRead());
        assertFalse(method.isWrite());
    }

    @Test
    @DisplayName("POST method should identify correctly")
    void testPostMethod() {
        HttpMethod method = HttpMethod.POST;

        assertFalse(method.isGet());
        assertTrue(method.isPost());
        assertFalse(method.isPut());
        assertFalse(method.isDelete());
        assertFalse(method.isPatch());
        assertFalse(method.isRead());
        assertTrue(method.isWrite());
    }

    @Test
    @DisplayName("PUT method should identify correctly")
    void testPutMethod() {
        HttpMethod method = HttpMethod.PUT;

        assertFalse(method.isGet());
        assertFalse(method.isPost());
        assertTrue(method.isPut());
        assertFalse(method.isDelete());
        assertFalse(method.isPatch());
        assertFalse(method.isRead());
        assertTrue(method.isWrite());
    }

    @Test
    @DisplayName("DELETE method should identify correctly")
    void testDeleteMethod() {
        HttpMethod method = HttpMethod.DELETE;

        assertFalse(method.isGet());
        assertFalse(method.isPost());
        assertFalse(method.isPut());
        assertTrue(method.isDelete());
        assertFalse(method.isPatch());
        assertFalse(method.isRead());
        assertTrue(method.isWrite());
    }

    @Test
    @DisplayName("PATCH method should identify correctly")
    void testPatchMethod() {
        HttpMethod method = HttpMethod.PATCH;

        assertFalse(method.isGet());
        assertFalse(method.isPost());
        assertFalse(method.isPut());
        assertFalse(method.isDelete());
        assertTrue(method.isPatch());
        assertFalse(method.isRead());
        assertTrue(method.isWrite());
    }

    @Test
    @DisplayName("name() static method should return correct method name")
    void testNameMethod() {
        assertEquals("GET", HttpMethod.name(HttpMethod.GET));
        assertEquals("POST", HttpMethod.name(HttpMethod.POST));
        assertEquals("PUT", HttpMethod.name(HttpMethod.PUT));
        assertEquals("DELETE", HttpMethod.name(HttpMethod.DELETE));
        assertEquals("PATCH", HttpMethod.name(HttpMethod.PATCH));
    }

    @Test
    @DisplayName("All HTTP methods should have correct values")
    void testAllMethods() {
        HttpMethod[] methods = HttpMethod.values();
        assertEquals(5, methods.length);

        // Verify all expected values exist
        assertNotNull(HttpMethod.valueOf("GET"));
        assertNotNull(HttpMethod.valueOf("POST"));
        assertNotNull(HttpMethod.valueOf("PUT"));
        assertNotNull(HttpMethod.valueOf("DELETE"));
        assertNotNull(HttpMethod.valueOf("PATCH"));
    }

    @Test
    @DisplayName("Read methods should be identified correctly")
    void testReadMethods() {
        assertTrue(HttpMethod.GET.isRead());

        // Non-read methods
        assertFalse(HttpMethod.POST.isRead());
        assertFalse(HttpMethod.PUT.isRead());
        assertFalse(HttpMethod.DELETE.isRead());
        assertFalse(HttpMethod.PATCH.isRead());
    }

    @Test
    @DisplayName("Write methods should be identified correctly")
    void testWriteMethods() {
        assertFalse(HttpMethod.GET.isWrite());

        assertTrue(HttpMethod.POST.isWrite());
        assertTrue(HttpMethod.PUT.isWrite());
        assertTrue(HttpMethod.DELETE.isWrite());
        assertTrue(HttpMethod.PATCH.isWrite());
    }
}
