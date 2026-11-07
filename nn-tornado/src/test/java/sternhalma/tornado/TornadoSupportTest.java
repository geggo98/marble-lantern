package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.TornadoVMBackendType;

class TornadoSupportTest {

    @BeforeEach
    void requireTornado() {
        TornadoAssumptions.assumeTornado();
    }

    @Test
    void aUsableStatusNamesBackendAndDeviceAndNoReason() {
        TornadoSupport.Status status = TornadoSupport.status();
        assertTrue(status.usable());
        assertFalse(status.backend().isEmpty(), status.toString());
        assertFalse(status.device().isEmpty(), status.toString());
        assertTrue(status.devices() >= 1, status.toString());
        assertEquals("", status.reason());
    }

    @Test
    void theStatusDescribesTheDefaultDevice() {
        TornadoSupport.Status status = TornadoSupport.status();
        TornadoDevice device = TornadoSupport.defaultDevice();
        assertEquals(status.backend(), device.getTornadoVMBackend().name());
        assertEquals(status.device(), device.getPhysicalDevice().getDeviceName());
    }

    @Test
    void lookupByBackendFindsTheDefaultDevicesBackend() {
        TornadoDevice expected = TornadoSupport.defaultDevice();
        Optional<TornadoDevice> found = TornadoSupport.device(expected.getTornadoVMBackend());
        assertTrue(found.isPresent());
        assertEquals(expected.getTornadoVMBackend(), found.get().getTornadoVMBackend());
        assertEquals(expected.getPhysicalDevice().getDeviceName(), found.get().getPhysicalDevice().getDeviceName());
    }

    @Test
    void lookupOfAnUninstalledBackendIsEmpty() {
        // One SDK archive carries one GPU backend; CUDA (PTX) exists only in the Linux archive.
        assumeTrue(TornadoSupport.defaultDevice().getTornadoVMBackend() != TornadoVMBackendType.CUDA);
        assertEquals(Optional.empty(), TornadoSupport.device(TornadoVMBackendType.CUDA));
    }
}
