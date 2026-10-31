import UIKit
import HLShared

/// Uses Apple's actual Liquid Glass button material. Content remains in shared Compose UI.
final class HLIOSGlassButtons: NSObject, HLNativeGlassButtonFactory {
    func makeButton(symbol: String, label: String, filled: Bool, action: HLNativeGlassAction) -> UIView {
        let button = HLGlassControl(frame: .zero)
        button.addAction(UIAction { _ in action.invoke() }, for: .touchUpInside)
        button.configure(symbol: symbol, label: label, enabled: true, filled: filled)
        return button
    }

    func updateButton(view: UIView, symbol: String, label: String, enabled: Bool, filled: Bool) {
        (view as? HLGlassControl)?.configure(symbol: symbol, label: label, enabled: enabled, filled: filled)
    }
}

/// Keep a 48 pt hit target while the visible composer control stays compact.
private final class HLGlassControl: UIButton {
    private let visual = HLGlassButton(frame: .zero)
    private var visualSize: CGFloat = 48
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        visual.isUserInteractionEnabled = false
        addSubview(visual)
    }
    required init?(coder: NSCoder) { fatalError("Programmatic control") }
    override var isHighlighted: Bool { didSet { visual.isHighlighted = isHighlighted } }
    func configure(symbol: String, label: String, enabled: Bool, filled: Bool) {
        visualSize = label == "附件" ? 32 : (filled ? 36 : 48)
        accessibilityLabel = label
        isAccessibilityElement = true
        isEnabled = enabled
        visual.configure(symbol: symbol, label: label, enabled: enabled, filled: filled)
        visual.isAccessibilityElement = false
        setNeedsLayout()
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        let edge = min(visualSize, min(bounds.width, bounds.height))
        visual.frame = CGRect(x: (bounds.width - edge) / 2, y: (bounds.height - edge) / 2, width: edge, height: edge)
    }
}

private final class HLGlassButton: UIButton {
    private var symbolName = "plus"
    private var filled = false
    private var compact = false
    private var transparencyObserver: NSObjectProtocol?

    override init(frame: CGRect) {
        super.init(frame: frame)
        transparencyObserver = NotificationCenter.default.addObserver(forName: UIAccessibility.reduceTransparencyStatusDidChangeNotification, object: nil, queue: .main) { [weak self] _ in self?.refreshMaterial() }
    }
    required init?(coder: NSCoder) { fatalError("Programmatic glass control") }
    deinit { if let transparencyObserver { NotificationCenter.default.removeObserver(transparencyObserver) } }

    func configure(symbol: String, label: String, enabled: Bool, filled: Bool) {
        let small = label == "附件"
        let materialChanged = configuration == nil || symbolName != symbol || self.filled != filled || compact != small
        compact = small
        symbolName = symbol
        self.filled = filled
        accessibilityLabel = label
        isAccessibilityElement = true
        isEnabled = enabled
        if materialChanged { refreshMaterial() }
    }

    private func refreshMaterial() {
        var config: UIButton.Configuration
        var usesGlass = false
        if compact {
            config = .plain()
        } else if #available(iOS 26.0, *), !UIAccessibility.isReduceTransparencyEnabled {
            config = filled ? .prominentGlass() : .glass()
            usesGlass = true
        } else {
            config = filled ? .filled() : .tinted()
        }
        config.cornerStyle = .capsule
        config.buttonSize = .large
        let inset: CGFloat = filled ? 9 : (compact ? 8 : 12)
        config.contentInsets = NSDirectionalEdgeInsets(top: inset, leading: inset, bottom: inset, trailing: inset)
        let symbol = UIImage.SymbolConfiguration(pointSize: compact ? 16 : (filled ? 16 : 18), weight: compact ? .regular : .medium)
        config.image = UIImage(systemName: symbolName, withConfiguration: symbol)
        config.preferredSymbolConfigurationForImage = symbol
        if filled { config.baseBackgroundColor = UIColor(red: 0.208, green: 0.361, blue: 0.878, alpha: 1) }
        else if !usesGlass { config.baseBackgroundColor = .secondarySystemBackground }
        config.baseForegroundColor = filled ? .white : .label
        configuration = config
    }
}
