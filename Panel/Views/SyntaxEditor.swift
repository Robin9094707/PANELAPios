import SwiftUI
import UIKit

struct SyntaxEditor: UIViewRepresentable {
    @Binding var text: String
    let colors: Bool
    var editable = true
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
                let start = max(0, center - 16000); range = (view.text as NSString).lineRange(for: NSRange(location: start, length: min(32000, length - start)))
            }
            storage.beginEditing()
            let old = NSIntersectionRange(colored, NSRange(location: 0, length: length))
            if old.length > 0 { storage.addAttribute(.foregroundColor, value: UIColor.label, range: old) }
            storage.addAttribute(.foregroundColor, value: UIColor.label, range: range)
            if parent.colors {
                SyntaxHighlight.apply(to: storage, range: range)
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
        view.isEditable = editable
        let changedColors = context.coordinator.parent.colors != colors
        context.coordinator.parent = self
        if view.text != text { let selection = view.selectedRange; view.text = text; if NSMaxRange(selection) <= view.text.utf16.count { view.selectedRange = selection }; context.coordinator.schedule(view) }
        else if changedColors { context.coordinator.schedule(view) }
    }
}
