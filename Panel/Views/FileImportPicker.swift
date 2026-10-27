import SwiftUI
import UniformTypeIdentifiers
import UIKit
import Combine

/// A reference mailbox survives SwiftUI's cached presentation/onDismiss closures.
@MainActor final class FileImportHandoff: ObservableObject {
    struct Selection: Equatable {
        let file: URL
        let target: String?
    }
    private var target: String?
    private var selection: Selection?
    private var error: String?
    func begin(target: String? = nil) {
        self.target = target; selection = nil; error = nil
    }
    func receive(_ result: Result<[URL], Error>) {
        selection = nil; error = nil
        switch result {
        case .success(let urls): selection = urls.first.map { Selection(file: $0, target: target) }
        case .failure(let issue): error = issue.localizedDescription
        }
    }
    func takeSelection() -> Selection? { defer { selection = nil }; return selection }
    func take() -> URL? { takeSelection()?.file }
    func takeError() -> String? { defer { error = nil }; return error }

}

/// Import a local copy instead of opening a provider-owned URL in place. JAR types
/// vary between Files providers, so filter/validate the actual file after selection.
struct FileImportPicker: UIViewControllerRepresentable {
    var multiple = false
    var retainSelection = false
    let completion: (Result<[URL], Error>) -> Void
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        var parent: FileImportPicker
        private var completed = false
        init(_ parent: FileImportPicker) { self.parent = parent }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard !completed else { return }; completed = true
            guard parent.retainSelection else { parent.completion(.success(urls)); return }
            do {
                // Own the bytes before the Files provider revokes its temporary URL.
                let copied = try urls.map { source -> URL in
                    let access = source.startAccessingSecurityScopedResource()
                    defer { if access { source.stopAccessingSecurityScopedResource() } }
                    let folder = FileManager.default.temporaryDirectory.appendingPathComponent("import-" + UUID().uuidString)
                    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                    let target = folder.appendingPathComponent(source.lastPathComponent)
                    var coordinationError: NSError?, copyError: Error?
                    NSFileCoordinator().coordinate(readingItemAt: source, options: [], error: &coordinationError) { local in
                        do { try FileManager.default.copyItem(at: local, to: target) } catch { copyError = error }
                    }
                    if let error = coordinationError { try? FileManager.default.removeItem(at: folder); throw error }
                    if let error = copyError { try? FileManager.default.removeItem(at: folder); throw error }
                    return target
                }
                parent.completion(.success(copied))
            } catch { parent.completion(.failure(error)) }
        }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            guard !completed else { return }; completed = true
            parent.completion(.success([]))
        }
    }
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        picker.delegate = context.coordinator; picker.allowsMultipleSelection = multiple
        picker.shouldShowFileExtensions = true
        return picker
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) { context.coordinator.parent = self }
}

