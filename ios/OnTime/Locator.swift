import CoreLocation

/// One position for the city: asks once, then answers with the location or why there is none (as the page expects).
final class Locator: NSObject, CLLocationManagerDelegate {
    static let shared = Locator()
    enum Failure: String, Error { case off, denied, none }
    private let m = CLLocationManager()
    private var done: ((Result<CLLocation, Failure>) -> Void)?

    override init() { super.init(); m.delegate = self; m.desiredAccuracy = kCLLocationAccuracyHundredMeters }

    func locate(_ cb: @escaping (Result<CLLocation, Failure>) -> Void) {
        guard CLLocationManager.locationServicesEnabled() else { cb(.failure(.off)); return }
        done = cb
        switch m.authorizationStatus {
        case .notDetermined: m.requestWhenInUseAuthorization()
        case .denied, .restricted: finish(.failure(.denied))
        default: m.requestLocation()
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard done != nil else { return }
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: manager.requestLocation()
        case .denied, .restricted: finish(.failure(.denied))
        default: break
        }
    }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) { if let l = locations.last { finish(.success(l)) } }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) { finish(.failure(.none)) }
    private func finish(_ r: Result<CLLocation, Failure>) { let cb = done; done = nil; DispatchQueue.main.async { cb?(r) } }
}
