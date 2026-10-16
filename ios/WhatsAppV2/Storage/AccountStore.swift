//
//  AccountStore.swift
//  Accounts on disk, passwords in the Keychain.
//
//  ## The split, and why it is not optional
//
//  `SipAccount` is `Codable` and is written to a property list in Application Support.
//  A plist is a file: anything that can read the app's container can read it, which on a
//  jailbroken device or from a backup is a real audience. A SIP password is a credential
//  that places calls at the account holder's expense, so it never goes in there.
//
//  Passwords live in the Keychain under the account id, with
//  `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`:
//
//    * **AfterFirstUnlock** rather than WhenUnlocked, because the app must be able to
//      register — and therefore to authenticate — after a reboot while the phone is
//      still locked in somebody's pocket. WhenUnlocked would mean no incoming calls
//      until the user next typed their passcode.
//    * **ThisDeviceOnly**, so the credential is not carried to a new device in an
//      iCloud backup. The account list is; the passwords are re-entered.
//
//  The Android half encrypts the password column at rest for the same reason, and the
//  same rule applies here: it is never logged, and `SipAccount` has no field for it.
//

import Foundation
import Combine
import Security

@MainActor
final class AccountStore: ObservableObject {

    @Published private(set) var accounts: [SipAccount] = []

    private let fileURL: URL
    private let keychainService: String

    init(directory: URL? = nil, keychainService: String = "com.whatsappv2.sip.password") {
        self.keychainService = keychainService
        let base = directory ?? FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("WhatsAppV2", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        self.fileURL = base.appendingPathComponent("accounts.plist")
        load()
    }

    // MARK: - Reading

    func account(id: String) -> SipAccount? { accounts.first { $0.id == id } }

    /// The account outgoing calls use unless one is chosen: the default, or the first
    /// registered one, or simply the first. Never nil when there is any account at all,
    /// because a dialler that refuses to dial because no default is set is useless.
    func defaultAccount(registrations: [String: RegistrationStatus] = [:]) -> SipAccount? {
        accounts.first { $0.isDefault }
            ?? accounts.first { registrations[$0.id]?.isUsable == true }
            ?? accounts.first
    }

    // MARK: - Writing

    func save(_ account: SipAccount, password: String?) {
        var updated = account

        // Exactly one account is the default. Enforced here rather than in the editor,
        // because two defaults is a state the rest of the app has no answer for and the
        // editor is not the only thing that writes.
        if updated.isDefault {
            for index in accounts.indices where accounts[index].id != updated.id {
                accounts[index].isDefault = false
            }
        } else if accounts.allSatisfy({ $0.id == updated.id || !$0.isDefault }) {
            updated.isDefault = true
        }

        if let index = accounts.firstIndex(where: { $0.id == updated.id }) {
            accounts[index] = updated
        } else {
            accounts.append(updated)
        }

        // A nil password means "unchanged", not "clear it". The editor sends nil when
        // the user did not touch the field, and treating that as a clear would silently
        // break an account every time its label was edited.
        if let password, !password.isEmpty {
            setPassword(password, for: updated.id)
        }
        persist()
    }

    func delete(id: String) {
        accounts.removeAll { $0.id == id }
        deletePassword(for: id)
        persist()
    }

    func setRegistrationWanted(_ wanted: Bool, for id: String) {
        guard let index = accounts.firstIndex(where: { $0.id == id }) else { return }
        accounts[index].registrationWanted = wanted
        persist()
    }

    // MARK: - Persistence

    private func load() {
        guard let data = try? Data(contentsOf: fileURL) else { accounts = []; return }
        do {
            accounts = try PropertyListDecoder().decode([SipAccount].self, from: data)
        } catch {
            // A decode failure means the file is from an incompatible build or is
            // corrupt. It is NOT deleted: losing the user's account list silently is
            // worse than starting empty, and the file is the only copy of what they
            // typed. The passwords remain in the Keychain and are re-paired by id.
            NSLog("[accounts] could not read \(fileURL.lastPathComponent): \(error)")
            accounts = []
        }
    }

    private func persist() {
        do {
            let encoder = PropertyListEncoder()
            encoder.outputFormat = .binary
            let data = try encoder.encode(accounts)
            // Atomic, so a crash mid-write leaves the previous list rather than a
            // half-written file that will not decode.
            try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            NSLog("[accounts] could not write: \(error)")
        }
    }

    // MARK: - Keychain

    func password(for accountId: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String:            kSecClassGenericPassword,
            kSecAttrService as String:      keychainService,
            kSecAttrAccount as String:      accountId,
            kSecReturnData as String:       true,
            kSecMatchLimit as String:       kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func setPassword(_ password: String, for accountId: String) {
        guard let data = password.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String:       kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: accountId,
        ]
        let attributes: [String: Any] = [
            kSecValueData as String:       data,
            kSecAttrAccessible as String:  kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        // Update first, add on miss. SecItemAdd on an existing item returns
        // errSecDuplicateItem rather than overwriting, so an add-only implementation
        // leaves the OLD password in place after a change — an account that fails to
        // register with the password the user can see in the editor.
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var insert = query
            insert.merge(attributes) { current, _ in current }
            SecItemAdd(insert as CFDictionary, nil)
        }
    }

    private func deletePassword(for accountId: String) {
        let query: [String: Any] = [
            kSecClass as String:       kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: accountId,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
