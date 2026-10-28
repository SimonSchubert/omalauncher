package dev.omalauncher.linux

import org.junit.Assert.*
import org.junit.Test

class GuestPrintingConfigTest {
    @Test fun driverFlattensCopiesWithoutErasingSavedDefaults() {
        val original = "*PPD-Adobe: \"4.3\"\n*cupsManualCopies: True\n*DefaultPageSize: Letter\n*cupsFilter: \"application/vnd.cups-postscript 0 -\"\n"
        val updated = GuestPrintingConfig.driver(original)
        assertTrue(updated.contains("*DefaultPageSize: Letter"))
        assertFalse(updated.contains("*cupsFilter:"))
        assertTrue(updated.contains(GuestPrintingConfig.PDF_FILTER))
        assertEquals(updated, GuestPrintingConfig.driver(updated))
    }

    @Test fun driverOwnsManualCopiesRegardlessOfVendorDefault() {
        for (original in listOf("*cupsManualCopies: False", "*PPD-Adobe: \"4.3\"")) {
            val updated = GuestPrintingConfig.driver(original)
            assertTrue(updated.contains("*cupsManualCopies: True"))
            assertFalse(updated.contains("*cupsManualCopies: False"))
        }
    }

    @Test fun ordinaryApplicationsAndDaemonUseTheSameSocket() {
        assertTrue(GuestPortal.ENV.contains("CUPS_SERVER=${GuestPrintingConfig.SOCKET}"))
        assertTrue(GuestPrintingConfig.daemon.lines().contains("Listen ${GuestPrintingConfig.SOCKET}"))
    }

    @Test fun privateDaemonDoesNotListenOnTheNetwork() {
        val lines = GuestPrintingConfig.daemon.lines().map(String::trim)
        assertEquals(listOf("Listen ${GuestPrintingConfig.SOCKET}"), lines.filter { it.startsWith("Listen ") })
        assertFalse(lines.any { it.startsWith("Port ") || it.startsWith("SSLListen ") })
        assertTrue(lines.contains("DefaultShared No"))
        assertTrue(lines.contains("Browsing Off"))
    }

    @Test fun queueAndPdfHookUseTheManagedBridge() {
        assertTrue(GuestPrintingConfig.printer.contains("DeviceURI omalauncher-pdf:/"))
        assertTrue(GuestPrintingConfig.printer.contains("ErrorPolicy abort-job"))
        assertTrue(GuestPrintingConfig.pdf.contains("PostProcessing ${GuestPrintingConfig.FORWARDER}"))
        assertTrue(GuestPrintingConfig.files.contains("PeerCred Off"))
        assertFalse(GuestPrintingConfig.files.lines().any { it.trim() == "User root" })
    }
}
