import SwiftUI
import UIKit

extension Color {
    static let panelMint = Color(red: 0.30, green: 0.88, blue: 0.74)
}
struct PanelBackground: View {
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        ZStack {
            (scheme == .dark ? Color(red: 0.025, green: 0.05, blue: 0.085) : Color(red: 0.94, green: 0.97, blue: 0.97))
            GeometryReader { geo in
                Circle().fill(Color.teal.opacity(scheme == .dark ? 0.18 : 0.10)).frame(width: geo.size.width * 1.2).blur(radius: 95).offset(x: geo.size.width * 0.25, y: -180)
                Circle().fill(Color.indigo.opacity(0.12)).frame(width: geo.size.width).blur(radius: 110).offset(x: -geo.size.width * 0.6, y: geo.size.height * 0.6)
            }.accessibilityHidden(true)
        }.ignoresSafeArea()
    }
}
extension View {
    func panelScreen() -> some View { self.scrollContentBackground(.hidden).background { PanelBackground() } }
    func card() -> some View { self.padding(18).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24)).overlay { RoundedRectangle(cornerRadius: 24).strokeBorder(.primary.opacity(0.06)) } }
    func errorAlert(_ error: Binding<String?>) -> some View {
        alert("Hinweis", isPresented: Binding(get: { error.wrappedValue != nil }, set: { if !$0 { error.wrappedValue = nil } })) {
            Button("OK", role: .cancel) { error.wrappedValue = nil }
        } message: { Text(error.wrappedValue ?? "") }
    }
}
struct StatePill: View {
    let state: String
    var color: Color { switch state { case "running": return .green; case "starting", "stopping": return .yellow; case "offline": return .red; default: return .orange } }
    var title: String { switch state { case "running": return "Online"; case "starting": return "Startet"; case "stopping": return "Stoppt"; case "offline": return "Offline"; default: return "Unbekannt" } }
    var body: some View { HStack(spacing: 6) { Circle().fill(color).frame(width: 7, height: 7); Text(title).font(.caption.weight(.semibold)) }.padding(.horizontal, 10).padding(.vertical, 6).background(color.opacity(0.12), in: Capsule()) }
}
struct ShareItem: Identifiable { let id = UUID(); let url: URL }
struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [url], applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
struct Field: Identifiable {
    enum Kind { case text, number, toggle, multiline, secret, strings, json }
    let key: String
    let label: String
    var value: String = ""
    var kind: Kind = .text
    var hint: String = ""
    var options: [String] = []
    var id: String { key }
}
struct ActionForm: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    var detail: String = ""
    var fields: [Field] = []
    var destructive = false
    var submit = "Speichern"
    var allowed = true
    let perform: ([String: JSON]) async throws -> Void
    @State private var values: [String: String] = [:]
    @State private var error: String?
    @State private var busy = false
    @State private var confirm = false
    var body: some View {
        NavigationStack {
            Form {
                if !allowed { Section { Label("Keine Berechtigung für diese Aktion", systemImage: "lock.shield").foregroundStyle(.secondary) } }
                if !detail.isEmpty { Section { Text(detail).foregroundStyle(.secondary) } }
                ForEach(fields) { field in
                    Section {
                        if !field.options.isEmpty {
                            Picker(field.label, selection: binding(field)) { ForEach(field.options, id: \.self) { Text($0).tag($0) } }
                        } else { switch field.kind {
                        case .toggle:
                            Toggle(field.label, isOn: Binding(get: { values[field.key] == "true" }, set: { values[field.key] = $0 ? "true" : "false" }))
                        case .multiline, .strings, .json:
                            Text(field.label).font(.subheadline.weight(.semibold))
                            TextEditor(text: binding(field)).font(.system(.body, design: field.kind == .json ? .monospaced : .default)).frame(minHeight: 110).autocorrectionDisabled().textInputAutocapitalization(.never)
                        case .secret: SecureField(field.label, text: binding(field)).textInputAutocapitalization(.never).autocorrectionDisabled()
                        default:
                            TextField(field.label, text: binding(field)).keyboardType(field.kind == .number ? .numbersAndPunctuation : .default).textInputAutocapitalization(.never).autocorrectionDisabled()
                        }
                    } } footer: { if !field.hint.isEmpty { Text(field.hint) } }
                }
                Section {
                    Button(role: destructive ? .destructive : nil) { if destructive { confirm = true } else { save() } } label: {
                        HStack { Spacer(); if busy { ProgressView() }; Text(submit).fontWeight(.semibold); Spacer() }
                    }.disabled(busy || !allowed)
                }
            }.panelScreen().navigationTitle(title).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() }.disabled(busy) } }
                .interactiveDismissDisabled(busy)
                .errorAlert($error)
                .confirmationDialog("\(title)?", isPresented: $confirm, titleVisibility: .visible) {
                    Button(submit, role: .destructive) { save() }
                } message: { Text(detail) }
        }.onAppear { if values.isEmpty { values = Dictionary(uniqueKeysWithValues: fields.map { ($0.key, $0.value) }) } }
    }
    private func binding(_ f: Field) -> Binding<String> { Binding(get: { values[f.key] ?? f.value }, set: { values[f.key] = $0 }) }
    private func save() {
        guard allowed else { return }
        busy = true
        Task { do {
            var payload: [String: JSON] = [:]
            for f in fields {
                let v = values[f.key] ?? f.value
                switch f.kind {
                case .toggle: payload[f.key] = .bool(v == "true")
                case .number:
                    guard let n = Double(v), n.isFinite else { throw PanelError(message: "\(f.label): Bitte eine gültige Zahl eingeben.") }
                    payload[f.key] = .number(n)
                case .strings: payload[f.key] = .array(v.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.map(JSON.string))
                case .json: payload[f.key] = try JSON.parse(v)
                default: payload[f.key] = .string(v)
                }
            }
            try await perform(payload); dismiss()
        } catch { self.error = error.localizedDescription }; busy = false }
    }
}
struct Operation: Identifiable {
    let id = UUID()
    var title: String
    var detail: String = ""
    var path: String
    var method = "POST"
    var fields: [Field] = []
    var fixed: [String: JSON] = [:]
    var destructive = false
    var submit = "Speichern"
    var transform: (([String: JSON]) throws -> JSON)? = nil
}
struct OperationSheet: View {
    let api: PanelAPI
    let operation: Operation
    var onSuccess: () async -> Void
    @ObservedObject private var access: ServerAccess
    init(api: PanelAPI, operation: Operation, onSuccess: @escaping () async -> Void = {}) {
        self.api = api; self.operation = operation; self.onSuccess = onSuccess; self.access = api.access
    }
    var body: some View {
        ActionForm(title: operation.title, detail: operation.detail, fields: operation.fields, destructive: operation.destructive, submit: operation.submit, allowed: access.allows(path: operation.path, method: operation.method, body: .object(operation.fixed))) { values in
            guard !api.demo else { throw PanelError(message: "Der Demomodus zeigt Beispieldaten. Verbinde dein Panel, um Änderungen vorzunehmen.") }
            let payload = operation.fixed.merging(values) { _, new in new }
            let body = try operation.transform?(payload) ?? .object(payload)
            _ = try await api.request(operation.path, method: operation.method, body: body)
            await onSuccess()
        }
    }
}
struct JSONDetails: View {
    let title: String
    let data: JSON
    var body: some View {
        ScrollView { Text(data.pretty).font(.system(.footnote, design: .monospaced)).frame(maxWidth: .infinity, alignment: .leading).padding().textSelection(.enabled) }.panelScreen().navigationTitle(title).navigationBarTitleDisplayMode(.inline)
    }
}
func pathJoin(_ root: String, _ name: String) -> String { (root == "/" ? "" : root) + "/" + name }
