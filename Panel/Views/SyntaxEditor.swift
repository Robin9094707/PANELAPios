import SwiftUI
import UIKit

struct SyntaxEditor: UIViewRepresentable {
    @Binding var text: String
    let colors: Bool
    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: SyntaxEditor
        var pending: DispatchWorkItem?
        var colored = NSRange(location: 0, length: 0)
        init(_ parent: SyntaxEditor) { self.parent = parent }
        func textViewDidChange(_ view: UITextView) { parent.text = view.text; schedule(view) }
        func scrollViewDidScroll(_ scrollView: UIScrollView) { if let view = scrollView as? UITextView, view.text.utf16.count > 262144 { schedule(view) } }
        func schedule(_ view: UITextView) {
            pending?.cancel()
            let work = DispatchWorkItem { [weak self, weak view] in if let self, let view { self.highlight(view) } }
            pending = work; DispatchQueue.main.asyncAfter(deadline: .now() + 0.18, execute: work)
        }
        func highlight(_ view: UITextView) {
            guard view.markedTextRange == nil else { return }
            let storage = view.textStorage, length = storage.length
            guard length > 0 else { return }
            let selection = view.selectedRange
            var range = NSRange(location: 0, length: length)
            if length > 262144 {
                let point = CGPoint(x: 12, y: view.contentOffset.y + view.bounds.height / 2)
                let position = view.closestPosition(to: point) ?? view.beginningOfDocument
                let center = view.offset(from: view.beginningOfDocument, to: position)
                let start = max(0, center - 16000); range = NSRange(location: start, length: min(32000, length - start))
            }
            storage.beginEditing()
            let old = NSIntersectionRange(colored, NSRange(location: 0, length: length))
            if old.length > 0 { storage.addAttribute(.foregroundColor, value: UIColor.label, range: old) }
            storage.addAttribute(.foregroundColor, value: UIColor.label, range: range)
            if parent.colors {
                let patterns: [(String, UIColor)] = [
                    (#"(?m)^\s*[\w.\-\"']+(?=\s*[:=])"#, .systemTeal),
                    (#"\b(?:true|false|null|yes|no)\b"#, .systemPurple),
                    (#"(?<![\w])[-+]?\d+(?:\.\d+)?\b"#, .systemOrange),
                    (#"\"(?:[^\"\\]|\\.)*\"|'(?:[^']|'')*'"#, .systemBlue),
                    (#"(?m)(?:^|\s)#.*$"#, .secondaryLabel)
                ]
                let value = storage.string
                for (pattern, color) in patterns {
                    if let re = try? NSRegularExpression(pattern: pattern) { for match in re.matches(in: value, range: range) { storage.addAttribute(.foregroundColor, value: color, range: match.range) } }
                }
            }
            storage.endEditing(); colored = range
            view.selectedRange = selection
            view.typingAttributes = [.font: UIFont.monospacedSystemFont(ofSize: 14, weight: .regular), .foregroundColor: UIColor.label]
        }
    }
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.delegate = context.coordinator; view.backgroundColor = .clear
        view.font = .monospacedSystemFont(ofSize: 14, weight: .regular); view.textColor = .label
        view.autocapitalizationType = .none; view.autocorrectionType = .no; view.smartQuotesType = .no; view.smartDashesType = .no; view.smartInsertDeleteType = .no
        view.textContainerInset = UIEdgeInsets(top: 12, left: 14, bottom: 12, right: 14)
        view.textContainer.widthTracksTextView = true; view.layoutManager.allowsNonContiguousLayout = true
        view.alwaysBounceVertical = true; view.keyboardDismissMode = .interactive; view.accessibilityIdentifier = "fileEditor"
        return view
    }
    func updateUIView(_ view: UITextView, context: Context) {
        let changedColors = context.coordinator.parent.colors != colors
        context.coordinator.parent = self
        if view.text != text { let selection = view.selectedRange; view.text = text; if NSMaxRange(selection) <= view.text.utf16.count { view.selectedRange = selection }; context.coordinator.schedule(view) }
        else if changedColors { context.coordinator.schedule(view) }
    }
}
struct YAMLScalar: Identifiable {
    let line: Int
    let indent: String
    let key: String
    let value: String
    let comment: String
    let kind: String
    var id: Int { line }
    static func parse(_ text: String) -> [YAMLScalar] {
        guard text.utf8.count <= 524288 else { return [] }
        var rows: [YAMLScalar] = []
        let re = try! NSRegularExpression(pattern: #"^( *)([A-Za-z0-9_.-]+): +(.+)$"#)
        let lines = text.components(separatedBy: "\n")
        for (index, line) in lines.enumerated() {
            guard rows.count < 300, let m = re.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)), let a = Range(m.range(at: 1), in: line), let b = Range(m.range(at: 2), in: line), let c = Range(m.range(at: 3), in: line) else { continue }
            if let next = lines.dropFirst(index + 1).first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty && !$0.trimmingCharacters(in: .whitespaces).hasPrefix("#") }), next.prefix(while: { $0 == " " }).count > line[a].count { continue }
            var value = String(line[c]), comment = ""
            // Quoted values and collections stay in the full editor; no partial YAML parser guesses their semantics.
            guard !value.hasPrefix("\""), !value.hasPrefix("'"), !value.contains("\\"), !value.contains("\t") else { continue }
            if let marker = value.range(of: " #") { comment = String(value[marker.lowerBound...]); value = String(value[..<marker.lowerBound]) }
            guard !value.isEmpty, value.range(of: #"^[A-Za-z0-9 _./:@+-]+$"#, options: .regularExpression) != nil else { continue }
            let kind = ["true", "false"].contains(value) ? "bool" : Double(value) != nil ? "number" : "string"
            rows.append(YAMLScalar(line: index, indent: String(line[a]), key: String(line[b]), value: value, comment: comment, kind: kind))
        }; return rows
    }
    func replacement(_ value: String) throws -> String {
        guard !value.contains("\n"), !value.contains("\r") else { throw PanelError(message: "Einzeiligen Wert eingeben.") }
        let encoded: String
        if kind == "bool" { guard ["true", "false"].contains(value) else { throw PanelError(message: "true oder false erwartet.") }; encoded = value }
        else if kind == "number" { guard let n = Double(value), n.isFinite else { throw PanelError(message: "Gültige Zahl erwartet.") }; encoded = value }
        else { encoded = JSON.string(value).pretty }
        return indent + key + ": " + encoded + comment
    }
}
struct YAMLForm: View {
    @Binding var text: String
    @Environment(\.dismiss) private var dismiss
    @State private var rows: [YAMLScalar] = []
    @State private var edits: [Int: String] = [:]
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                Section { Text("Schnellbearbeitung einfacher, unzitierter YAML-Werte. Komplexe Strukturen, Listen, Anker und zitierte Werte bearbeitest du im Texteditor. Bis 300 Felder / 512 KiB.").font(.footnote).foregroundStyle(.secondary) }
                ForEach(rows) { row in
                    Section {
                        let binding = Binding<String>(get: { edits[row.line] ?? row.value }, set: { edits[row.line] = $0 })
                        if row.kind == "bool" { Toggle(row.key, isOn: Binding(get: { binding.wrappedValue == "true" }, set: { binding.wrappedValue = $0 ? "true" : "false" })) }
                        else { TextField(row.key, text: binding).textInputAutocapitalization(.never).autocorrectionDisabled() }
                    } header: { Text("\(row.key) · Zeile \(row.line + 1)") }
                }
                if rows.isEmpty { Text("Keine einfachen Formularfelder gefunden. Nutze den vollständigen Texteditor.").foregroundStyle(.secondary) }
            }.panelScreen().navigationTitle("YAML-Werte").errorAlert($error)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("Übernehmen") { apply() } } }
                .onAppear { rows = YAMLScalar.parse(text) }
        }
    }
    func apply() { do { var lines = text.components(separatedBy: "\n"); for row in rows { if let edit = edits[row.line], edit != row.value { lines[row.line] = try row.replacement(edit) } }; text = lines.joined(separator: "\n"); dismiss() } catch { self.error = error.localizedDescription } }
}
