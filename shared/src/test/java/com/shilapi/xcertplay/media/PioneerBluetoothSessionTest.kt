package com.shilapi.xcertplay.media
import org.junit.Assert.*
import org.junit.Test
class PioneerBluetoothSessionTest {
 private class Hw : PioneerUsbHardware {
  var source=201; var availability=3; var stock=false
  override fun current()=source
  override fun status()=availability
  override fun storagePresent()=stock
  override fun select(source:Int){this.source=source}
  override fun awaitSource(source:Int)=this.source==source
  override fun status(value:Int):Unit=error("must not change native availability")
  override fun watchdog():PioneerUsbWatchdog=error("must not launch helper")
 }
 @Test fun nativeRouteRestoresPreviousSource(){ val h=Hw();val s=PioneerBluetoothSession(h);assertTrue(s.start{true});assertEquals(205,h.source);assertTrue(s.ready);s.close();assertEquals(201,h.source);assertEquals(3,h.availability) }
 @Test fun callYieldsAndReturns(){val h=Hw();val s=PioneerBluetoothSession(h);s.start{true};h.source=206;assertTrue(s.tick());assertFalse(s.ready);h.source=0;assertTrue(s.tick());assertEquals(205,h.source)}
 @Test fun manualSwitchIsRespected(){val h=Hw();val s=PioneerBluetoothSession(h);s.start{true};h.source=201;assertFalse(s.tick());assertEquals(201,h.source)}
 @Test fun stockPlaybackIsNeverMixed(){val h=Hw();val s=PioneerBluetoothSession(h);s.start{true};h.stock=true;assertFalse(s.tick());assertEquals(205,h.source)}
 @Test fun unavailableSourceIsNeverSpoofed(){val h=Hw();h.availability=2;val s=PioneerBluetoothSession(h);try{s.start{true};fail()}catch(_:IllegalStateException){};assertEquals(201,h.source);assertEquals(2,h.availability)}
 @Test fun closingDuringCallDoesNotSwitchSource(){val h=Hw();val s=PioneerBluetoothSession(h);s.start{true};h.source=206;s.close();assertEquals(206,h.source)}
}
