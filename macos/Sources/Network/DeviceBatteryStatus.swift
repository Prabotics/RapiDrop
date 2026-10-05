import Foundation
import IOKit.ps

public struct DeviceBatteryStatus: Sendable, Equatable {
  public let level: Int
  public let isCharging: Bool

  public init(level: Int, isCharging: Bool) {
    self.level = level
    self.isCharging = isCharging
  }

  public static func current() -> DeviceBatteryStatus? {
    guard let snapshot = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
          let sources = IOPSCopyPowerSourcesList(snapshot)?.takeRetainedValue() as? [CFTypeRef]
    else { return nil }

    for source in sources {
      guard let desc = IOPSGetPowerSourceDescription(snapshot, source)?.takeUnretainedValue() as? [String: Any],
            let currentCap = desc[kIOPSCurrentCapacityKey] as? Int,
            let maxCap = desc[kIOPSMaxCapacityKey] as? Int,
            maxCap > 0
      else { continue }

      let percent = max(0, min(100, (currentCap * 100) / maxCap))
      let isCharging = (desc[kIOPSIsChargingKey] as? Bool) ?? ((desc[kIOPSIsChargingKey] as? Int) == 1)
      return DeviceBatteryStatus(level: percent, isCharging: isCharging)
    }
    return nil
  }
}
