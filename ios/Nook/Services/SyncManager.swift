import Foundation
import Observation
import UIKit

/// Account, server connection and the push/pull loop.
@MainActor
@Observable
final class SyncManager {
    enum Status: Equatable {
        case idle
        case syncing
        case failed(String)
    }

    /// What the user must decide after signing in on a device that already has data.
    enum LoginOutcome {
        case done
        case needsMergeDecision
    }

    private(set) var serverURL: String
    private(set) var username: String?
    private(set) var status: Status = .idle
    private(set) var lastSyncAt: Date?
    private(set) var serverInfo: ServerInfo?
    private(set) var isLoggedIn: Bool
    /// Offline mode: never talk to the sync server. Edits queue up locally
    /// and go out once it's switched off.
    var offline: Bool {
        didSet {
            UserDefaults.standard.set(offline, forKey: Self.offlineKey)
            if offline { debounce?.cancel(); status = .idle } else { Task { await syncNow() } }
        }
    }

    @ObservationIgnored private let store: HomeStore
    @ObservationIgnored private var token: String?
    @ObservationIgnored private var debounce: Task<Void, Never>?
    @ObservationIgnored private var timer: Task<Void, Never>?
    @ObservationIgnored private var running = false
    @ObservationIgnored private var again = false

    private static let urlKey = "sync.serverURL"
    private static let userKey = "sync.username"
    private static let lastKey = "sync.lastSyncAt"
    private static let tokenAccount = "sync.token"
    private static let offlineKey = "sync.offline"

    init(store: HomeStore) {
        self.store = store
        let defaults = UserDefaults.standard
        let url = defaults.string(forKey: Self.urlKey) ?? ""
        let savedToken = Keychain.get(Self.tokenAccount)
        serverURL = url
        username = defaults.string(forKey: Self.userKey)
        lastSyncAt = defaults.object(forKey: Self.lastKey) as? Date
        token = savedToken
        isLoggedIn = savedToken != nil && !url.isEmpty
        offline = defaults.bool(forKey: Self.offlineKey)
        store.onLocalChange = { [weak self] in self?.scheduleSync() }
        store.files.remoteFetcher = { [weak self] id in await self?.fetchFile(id) }
    }

    var client: APIClient? {
        guard let url = APIClient.normalize(serverURL) else { return nil }
        return APIClient(baseURL: url, token: token)
    }

    /// Signed in and allowed to reach the server right now.
    var isOnline: Bool { isLoggedIn && !offline }
    var canGenerateIcons: Bool { isOnline && (serverInfo?.features.icons ?? false) }
    var canServerCutout: Bool { isOnline && (serverInfo?.features.cutout ?? false) }

    // MARK: Account

    func checkServer(_ raw: String) async throws -> ServerInfo {
        guard let url = APIClient.normalize(raw) else { throw APIError.invalidURL }
        return try await APIClient(baseURL: url).info()
    }

    func signIn(server raw: String, username: String, password: String, register: Bool,
                inviteCode: String = "") async throws -> LoginOutcome {
        guard let url = APIClient.normalize(raw) else { throw APIError.invalidURL }
        let api = APIClient(baseURL: url)
        let info = try await api.info()
        let auth = try await api.login(username: username.trimmed, password: password,
                                       device: UIDevice.current.name, register: register, inviteCode: inviteCode)
        serverURL = url.absoluteString
        token = auth.token
        offline = false
        self.username = auth.user.username
        serverInfo = info
        persistAccount()

        // A fresh account, or a fresh device: nothing to decide.
        let remote = try await APIClient(baseURL: url, token: auth.token).sync(cursor: 0, changes: [], limit: 1)
        if !store.hasAnyData {
            store.cursor = 0
            isLoggedIn = true
            await syncNow()
            return .done
        }
        if remote.changes.isEmpty {
            store.markAllPending()
            isLoggedIn = true
            await syncNow()
            return .done
        }
        return .needsMergeDecision
    }

    /// Second half of sign-in when both this device and the account have data.
    func resolveMerge(keepLocal: Bool) async {
        if keepLocal {
            store.markAllPending()
        } else {
            store.wipeLocal()
        }
        store.cursor = 0
        isLoggedIn = true
        await syncNow()
    }

    /// Backs out of a sign-in the user cancelled at the merge prompt.
    func abandonSignIn() async {
        try? await client?.logout()
        clearAccount()
    }

    func signOut(wipeLocal: Bool) async {
        try? await client?.logout()
        clearAccount()
        if wipeLocal { store.wipeLocal() }
    }

    func changePassword(old: String, new: String) async throws {
        guard let client else { throw APIError.invalidURL }
        try await client.changePassword(old: old, new: new)
    }

    func deleteAccount(password: String) async throws {
        guard let client else { throw APIError.invalidURL }
        try await client.deleteAccount(password: password)
        clearAccount()
    }

    private func clearAccount() {
        token = nil
        username = nil
        isLoggedIn = false
        serverInfo = nil
        status = .idle
        lastSyncAt = nil
        store.cursor = 0
        persistAccount()
    }

    private func persistAccount() {
        let d = UserDefaults.standard
        d.set(serverURL, forKey: Self.urlKey)
        d.set(username, forKey: Self.userKey)
        d.set(lastSyncAt, forKey: Self.lastKey)
        Keychain.set(token, for: Self.tokenAccount)
    }

    func refreshServerInfo() async {
        guard isOnline, let client else { return }
        serverInfo = try? await client.info()
    }

    // MARK: Sync loop

    func scheduleSync(after seconds: Double = 2) {
        guard isOnline else { return }
        debounce?.cancel()
        debounce = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            await self?.syncNow()
        }
    }

    func startPeriodicSync() {
        timer?.cancel()
        timer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                await self?.syncNow()
            }
        }
    }

    func stopPeriodicSync() {
        timer?.cancel()
        timer = nil
    }

    func syncNow() async {
        guard isOnline, let client else { return }
        if running { again = true; return }
        running = true
        status = .syncing
        defer { running = false }
        do {
            repeat {
                again = false
                try await runOnce(client)
            } while again
            status = .idle
            lastSyncAt = Date()
            persistAccount()
            if serverInfo == nil { serverInfo = try? await client.info() }
        } catch is CancellationError {
            status = .idle
        } catch APIError.unauthorized {
            status = .failed("登录已失效，请重新登录")
            clearAccount()
            status = .failed("登录已失效，请重新登录")
        } catch {
            status = .failed(error.localizedDescription)
        }
    }

    private func runOnce(_ client: APIClient) async throws {
        // Photos first, so records never point at files the server lacks.
        for fileId in store.pendingUploads {
            guard let data = store.files.data(fileId) else { store.markUploaded(fileId); continue }
            do {
                try await client.upload(fileId: fileId, data: data)
                store.markUploaded(fileId)
            } catch APIError.server(let code, _) where (400..<500).contains(code) {
                store.markUploaded(fileId)  // rejected for good (too large, bad type): don't retry forever
            }
        }

        var pulledAnything = false
        while true {
            let outgoing = store.outgoingChanges(limit: 400)
            let response = try await client.sync(cursor: store.cursor, changes: outgoing.map(\.change))
            if response.reset == true {
                // The server lost data (restored from backup): upload everything again.
                store.markAllPending()
                continue
            }
            store.acknowledge(outgoing)
            store.applyRemote(response.changes)
            store.cursor = response.cursor
            pulledAnything = pulledAnything || !response.changes.isEmpty
            if !response.hasMore && store.pendingCount == 0 { break }
            if !response.hasMore && outgoing.isEmpty { break }
        }
        if pulledAnything { store.repairOrphans() }
    }

    func fetchFile(_ id: String) async -> Data? {
        guard isOnline, let client else { return nil }
        return try? await client.download(fileId: id)
    }
}
