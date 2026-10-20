import SwiftUI
import UniformTypeIdentifiers
import UIKit

/// Import a local copy instead of opening a provider-owned URL in place. JAR types
/// vary between Files providers, so filter/validate the actual file after selection.
struct FileImportPicker: UIViewControllerRepresentable {
    var multiple = false
    let completion: (Result<[URL], Error>) -> Void
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let parent: FileImportPicker
        init(_ parent: FileImportPicker) { self.parent = parent }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            parent.completion(.success(urls))
        }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { parent.completion(.success([])) }
    }
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        picker.delegate = context.coordinator; picker.allowsMultipleSelection = multiple
        picker.shouldShowFileExtensions = true
        return picker
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
}
