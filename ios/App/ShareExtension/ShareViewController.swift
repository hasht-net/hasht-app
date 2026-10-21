import UIKit
import ImageIO

private enum ShareStyle {
    static let accent = UIColor(red: 91 / 255, green: 110 / 255, blue: 225 / 255, alpha: 1)
    static let canvas = UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.075, alpha: 1) : .white }
    static let surface = UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.13, alpha: 1) : UIColor(white: 0.965, alpha: 1) }
    static let selected = UIColor { $0.userInterfaceStyle == .dark ? accent.withAlphaComponent(0.18) : accent.withAlphaComponent(0.07) }

    static func label(_ size: CGFloat, weight: UIFont.Weight = .regular, color: UIColor = .label) -> UILabel {
        let label = UILabel()
        label.font = UIFontMetrics.default.scaledFont(for: .systemFont(ofSize: size, weight: weight))
        label.adjustsFontForContentSizeCategory = true
        label.textColor = color
        return label
    }

    static func stack(_ views: [UIView], axis: NSLayoutConstraint.Axis = .vertical, spacing: CGFloat = 0) -> UIStackView {
        let stack = UIStackView(arrangedSubviews: views)
        stack.axis = axis
        stack.spacing = spacing
        return stack
    }

    static func pin(_ child: UIView, in parent: UIView, inset: CGFloat = 0) {
        child.translatesAutoresizingMaskIntoConstraints = false
        parent.addSubview(child)
        NSLayoutConstraint.activate([
            child.leadingAnchor.constraint(equalTo: parent.leadingAnchor, constant: inset),
            child.trailingAnchor.constraint(equalTo: parent.trailingAnchor, constant: -inset),
            child.topAnchor.constraint(equalTo: parent.topAnchor, constant: inset),
            child.bottomAnchor.constraint(equalTo: parent.bottomAnchor, constant: -inset)
        ])
    }
}

@MainActor
final class ShareViewController: UIViewController, UITableViewDataSource, UITableViewDelegate, UISearchBarDelegate, UITextViewDelegate {
    private let table = UITableView(frame: .zero, style: .plain)
    private let search = UISearchBar()
    private let searchContainer = UIView()
    private var keyboardVisible = false
    private let filter = UISegmentedControl(items: ["All", "People", "Channels"])
    private let text = UITextView()
    private let placeholder = ShareStyle.label(15, color: .tertiaryLabel)
    private let account = ShareStyle.label(12, color: .secondaryLabel)
    private let status = ShareStyle.label(13, color: .systemRed)
    private let preview = UIView()
    private let previewIcon = UIImageView()
    private let previewTitle = ShareStyle.label(13, weight: .semibold)
    private let previewDetail = ShareStyle.label(12, color: .secondaryLabel)
    private let selectionTitle = ShareStyle.label(13, weight: .medium)
    private let selectionDetail = ShareStyle.label(12, color: .secondaryLabel)
    private let sendButton = UIButton(type: .system)
    private let progress = UIActivityIndicatorView(style: .medium)
    private var sections: [(title: String, items: [ShareDestination])] = []
    private var destinations: [ShareDestination] = []
    private var selected: ShareDestination?
    private var content: ShareContent?
    private var api: ShareAPI?
    private var work: Task<Void, Never>?
    private var uploaded: [String] = []
    private let clientID = UUID().uuidString
    private var sending = false
    private var attempted = false
    private var finished = false
    private var loadFailed = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ShareStyle.canvas
        view.tintColor = ShareStyle.accent
        preferredContentSize = CGSize(width: 560, height: 760)
        buildInterface()
        NotificationCenter.default.addObserver(self, selector: #selector(keyboardChanged(_:)), name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
        work = Task { [weak self] in await self?.prepare() }
    }

    private func buildInterface() {
        let brand = UIImageView(image: UIImage(systemName: "number", withConfiguration: UIImage.SymbolConfiguration(pointSize: 19, weight: .bold)))
        brand.tintColor = .white
        brand.backgroundColor = ShareStyle.accent
        brand.contentMode = .center
        brand.layer.cornerRadius = 11
        brand.widthAnchor.constraint(equalToConstant: 38).isActive = true
        brand.heightAnchor.constraint(equalToConstant: 38).isActive = true
        let title = ShareStyle.label(20, weight: .bold)
        title.text = "Share to Hasht"
        account.text = "Preparing your share…"
        let titles = ShareStyle.stack([title, account], spacing: 3)
        let close = UIButton(type: .system)
        close.setImage(UIImage(systemName: "xmark", withConfiguration: UIImage.SymbolConfiguration(pointSize: 12, weight: .semibold)), for: .normal)
        close.tintColor = .secondaryLabel
        close.backgroundColor = ShareStyle.surface
        close.layer.cornerRadius = 16
        close.widthAnchor.constraint(equalToConstant: 32).isActive = true
        close.heightAnchor.constraint(equalToConstant: 32).isActive = true
        close.accessibilityLabel = "Cancel sharing"
        close.addTarget(self, action: #selector(cancel), for: .touchUpInside)
        let header = ShareStyle.stack([brand, titles, close], axis: .horizontal, spacing: 12)
        header.alignment = .center

        preview.backgroundColor = ShareStyle.surface
        preview.layer.cornerRadius = 16
        preview.layer.cornerCurve = .continuous
        previewIcon.tintColor = ShareStyle.accent
        previewIcon.contentMode = .scaleAspectFill
        previewIcon.backgroundColor = ShareStyle.selected
        previewIcon.clipsToBounds = true
        previewIcon.layer.cornerRadius = 9
        previewIcon.widthAnchor.constraint(equalToConstant: 40).isActive = true
        previewIcon.heightAnchor.constraint(equalToConstant: 40).isActive = true
        previewTitle.text = "Your share"
        previewDetail.text = "Content stays here until you send"
        let fileRow = ShareStyle.stack([previewIcon, ShareStyle.stack([previewTitle, previewDetail], spacing: 3)], axis: .horizontal, spacing: 11)
        fileRow.alignment = .center
        text.font = UIFontMetrics.default.scaledFont(for: .systemFont(ofSize: 15))
        text.adjustsFontForContentSizeCategory = true
        text.backgroundColor = .clear
        text.textColor = .label
        text.textContainerInset = .zero
        text.textContainer.lineFragmentPadding = 0
        text.heightAnchor.constraint(equalToConstant: 54).isActive = true
        text.delegate = self
        text.accessibilityLabel = "Message to share"
        placeholder.text = "Add a message…"
        placeholder.isUserInteractionEnabled = false
        placeholder.translatesAutoresizingMaskIntoConstraints = false
        text.addSubview(placeholder)
        NSLayoutConstraint.activate([
            placeholder.topAnchor.constraint(equalTo: text.topAnchor),
            placeholder.leadingAnchor.constraint(equalTo: text.leadingAnchor, constant: 1)
        ])
        let previewContents = ShareStyle.stack([fileRow, text], spacing: 12)
        ShareStyle.pin(previewContents, in: preview, inset: 14)

        search.delegate = self
        search.searchBarStyle = .minimal
        search.placeholder = "Search people or channels"
        search.backgroundImage = UIImage()
        search.searchTextField.backgroundColor = ShareStyle.surface
        search.searchTextField.font = .systemFont(ofSize: 15)
        search.searchTextField.layer.cornerRadius = 12
        search.searchTextField.clipsToBounds = true
        search.searchTextField.accessibilityLabel = "Search conversations and channels"
        search.setImage(UIImage(systemName: "magnifyingglass", withConfiguration: UIImage.SymbolConfiguration(pointSize: 15, weight: .medium)), for: .search, state: .normal)
        // UISearchBar adds its own side margins; align its field with the cards.
        let searchWrap = searchContainer
        search.translatesAutoresizingMaskIntoConstraints = false
        searchWrap.addSubview(search)
        NSLayoutConstraint.activate([
            search.leadingAnchor.constraint(equalTo: searchWrap.leadingAnchor, constant: -8),
            search.trailingAnchor.constraint(equalTo: searchWrap.trailingAnchor, constant: 8),
            search.topAnchor.constraint(equalTo: searchWrap.topAnchor),
            search.bottomAnchor.constraint(equalTo: searchWrap.bottomAnchor)
        ])
        filter.selectedSegmentIndex = 0
        filter.selectedSegmentTintColor = ShareStyle.canvas
        filter.backgroundColor = ShareStyle.surface
        filter.setTitleTextAttributes([.font: UIFont.systemFont(ofSize: 13, weight: .medium), .foregroundColor: UIColor.secondaryLabel], for: .normal)
        filter.setTitleTextAttributes([.foregroundColor: UIColor.label], for: .selected)
        filter.addTarget(self, action: #selector(filterChanged), for: .valueChanged)
        status.numberOfLines = 3
        status.isHidden = true
        let top = ShareStyle.stack([header, preview, searchWrap, filter, status], spacing: 16)
        top.setCustomSpacing(20, after: header)
        top.setCustomSpacing(4, after: preview)
        top.setCustomSpacing(8, after: searchWrap)
        top.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(top)

        table.backgroundColor = .clear
        table.separatorStyle = .none
        table.dataSource = self
        table.delegate = self
        table.keyboardDismissMode = .onDrag
        table.rowHeight = UITableView.automaticDimension
        table.estimatedRowHeight = 72
        table.sectionHeaderHeight = UITableView.automaticDimension
        table.estimatedSectionHeaderHeight = 40
        table.contentInset.bottom = 8
        if #available(iOS 15.0, *) { table.sectionHeaderTopPadding = 4 }
        table.register(ShareDestinationCell.self, forCellReuseIdentifier: "destination")
        table.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(table)

        let footer = UIView()
        footer.backgroundColor = ShareStyle.canvas
        let line = UIView()
        line.backgroundColor = .separator
        line.translatesAutoresizingMaskIntoConstraints = false
        footer.addSubview(line)
        NSLayoutConstraint.activate([
            line.topAnchor.constraint(equalTo: footer.topAnchor), line.leadingAnchor.constraint(equalTo: footer.leadingAnchor),
            line.trailingAnchor.constraint(equalTo: footer.trailingAnchor), line.heightAnchor.constraint(equalToConstant: 0.5)
        ])
        selectionTitle.text = "Choose a conversation"
        selectionDetail.text = "Select where to send your share"
        let selectedText = ShareStyle.stack([selectionTitle, selectionDetail], spacing: 4)
        selectionTitle.lineBreakMode = .byTruncatingTail
        selectionDetail.lineBreakMode = .byTruncatingTail
        selectedText.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        sendButton.addTarget(self, action: #selector(send), for: .touchUpInside)
        sendButton.widthAnchor.constraint(greaterThanOrEqualToConstant: 112).isActive = true
        let buttonWidth = sendButton.widthAnchor.constraint(equalToConstant: 128)
        buttonWidth.priority = .defaultHigh
        buttonWidth.isActive = true
        sendButton.setContentCompressionResistancePriority(.required, for: .horizontal)
        sendButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 48).isActive = true
        let actions = ShareStyle.stack([selectedText, sendButton], axis: .horizontal, spacing: 16)
        actions.alignment = .center
        let footerContents = ShareStyle.stack([actions])
        ShareStyle.pin(footerContents, in: footer, inset: 20)
        footer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(footer)
        NSLayoutConstraint.activate([
            top.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            top.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20),
            top.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20),
            table.topAnchor.constraint(equalTo: top.bottomAnchor, constant: 12),
            table.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 12),
            table.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -12),
            table.bottomAnchor.constraint(equalTo: footer.topAnchor),
            footer.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            footer.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            footer.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor)
        ])
        progress.hidesWhenStopped = true
        updateSendButton()
        updateCompactLayout()
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: { _ in self.updateCompactLayout() })
    }

    private func updateCompactLayout() {
        preview.isHidden = traitCollection.verticalSizeClass == .compact || view.bounds.height < 580 || (keyboardVisible && search.searchTextField.isFirstResponder)
        let composing = keyboardVisible && text.isFirstResponder
        searchContainer.isHidden = composing
        filter.isHidden = composing
    }

    @objc private func keyboardChanged(_ notification: Notification) {
        guard let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
        keyboardVisible = view.bounds.intersection(view.convert(frame, from: nil)).height > view.safeAreaInsets.bottom
        updateCompactLayout()
        let duration = notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double ?? 0.25
        UIView.animate(withDuration: duration) { self.view.layoutIfNeeded() }
    }

    deinit { NotificationCenter.default.removeObserver(self) }

    private func updateSendButton() {
        let hasContent = !(text.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !(content?.files.isEmpty ?? true)
        sendButton.isEnabled = !sending && (loadFailed || (selected != nil && content != nil && hasContent))
        var config = UIButton.Configuration.filled()
        config.title = loadFailed ? "Try again" : attempted && !sending ? "Retry" : sending ? "Sending" : "Send"
        config.image = sending ? nil : UIImage(systemName: loadFailed ? "arrow.clockwise" : "paperplane.fill")
        config.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 14, weight: .semibold)
        config.imagePadding = 8
        config.imagePlacement = .trailing
        config.showsActivityIndicator = sending
        config.baseBackgroundColor = ShareStyle.accent
        config.baseForegroundColor = .white
        config.cornerStyle = .medium
        config.contentInsets = NSDirectionalEdgeInsets(top: 12, leading: 18, bottom: 12, trailing: 18)
        config.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var attributes = incoming
            attributes.font = UIFontMetrics.default.scaledFont(for: .systemFont(ofSize: 15, weight: .semibold))
            return attributes
        }
        sendButton.configuration = config
        sendButton.accessibilityLabel = selected.map { "Send to \($0.name)" } ?? (loadFailed ? "Retry loading conversations" : "Select a conversation to send")
    }

    private func prepare() async {
        api?.cancel()
        cleanup()
        content = nil
        loadFailed = false
        status.isHidden = true
        updateSendButton()
        guard let auth = ShareSession.load() else {
            account.text = "No account connected"
            showEmpty(title: "Sign in to share", message: "Open Hasht and sign in to your instance, then share again.", symbol: "person.crop.circle.badge.exclamationmark")
            return
        }
        account.text = "\(auth.username)  ·  \(URL(string: auth.origin)?.host ?? auth.origin)"
        showEmpty(title: "Getting things ready", message: "Loading your conversations…", symbol: nil)
        let api = ShareAPI(auth: auth)
        self.api = api
        do {
            let content = try await ShareContent.read(extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            guard !Task.isCancelled else { try? FileManager.default.removeItem(at: content.directory); return }
            self.content = content
            configurePreview(content)
            destinations = try await api.destinations()
            refreshDestinations()
        } catch {
            loadFailed = true
            showEmpty(title: "Couldn't load your share", message: error.localizedDescription, symbol: "wifi.exclamationmark")
            updateSendButton()
        }
    }

    private func configurePreview(_ content: ShareContent) {
        text.text = content.text
        placeholder.isHidden = !content.text.isEmpty
        text.isEditable = true
        let files = content.files
        previewTitle.text = files.first.map { files.count == 1 ? $0.name : "\(files.count) attachments" } ?? "Shared text"
        previewDetail.text = files.isEmpty ? "Add or edit your message below" : files.prefix(3).map(\.name).joined(separator: " · ")
        let symbol = files.first.map { $0.type.hasPrefix("image/") ? "photo" : $0.type.hasPrefix("video/") ? "video" : "doc" } ?? "text.alignleft"
        previewIcon.image = UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: 19, weight: .medium))
        previewIcon.contentMode = .center
        if let first = files.first, first.type.hasPrefix("image/"),
           let source = CGImageSourceCreateWithURL(first.url as CFURL, nil),
           let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
               kCGImageSourceCreateThumbnailFromImageAlways: true,
               kCGImageSourceThumbnailMaxPixelSize: 120,
               kCGImageSourceCreateThumbnailWithTransform: true
           ] as CFDictionary) {
            previewIcon.image = UIImage(cgImage: image)
            previewIcon.contentMode = .scaleAspectFill
        }
    }

    @objc private func filterChanged() { refreshDestinations() }
    func searchBar(_ searchBar: UISearchBar, textDidChange searchText: String) { refreshDestinations() }
    func searchBarSearchButtonClicked(_ searchBar: UISearchBar) { view.endEditing(true) }
    func textViewDidChange(_ textView: UITextView) {
        placeholder.isHidden = !textView.text.isEmpty
        updateSendButton()
    }

    private func refreshDestinations() {
        let query = search.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let matches = destinations.filter {
            (filter.selectedSegmentIndex != 1 || $0.kind == .directMessage) &&
            (filter.selectedSegmentIndex != 2 || $0.kind == .channel) &&
            (query.isEmpty || ($0.name + " " + $0.detail + " " + $0.subtitle).localizedCaseInsensitiveContains(query))
        }
        sections = []
        for destination in matches {
            if let index = sections.firstIndex(where: { $0.title == destination.detail }) {
                sections[index].items.append(destination)
            } else { sections.append((destination.detail, [destination])) }
        }
        table.backgroundView = nil
        if matches.isEmpty {
            showEmpty(title: destinations.isEmpty ? "No conversations yet" : "No matches found",
                      message: destinations.isEmpty ? "Open Hasht to start a DM or join a text channel." : "Try another name or channel.", symbol: "bubble.left.and.bubble.right")
        }
        table.reloadData()
    }

    private func showEmpty(title: String, message: String, symbol: String?) {
        let icon = UIImageView(image: symbol.flatMap { UIImage(systemName: $0, withConfiguration: UIImage.SymbolConfiguration(pointSize: 28, weight: .light)) })
        icon.tintColor = .tertiaryLabel
        icon.contentMode = .center
        let heading = ShareStyle.label(16, weight: .semibold)
        heading.text = title
        let detail = ShareStyle.label(13, color: .secondaryLabel)
        detail.text = message
        detail.numberOfLines = 0
        detail.textAlignment = .center
        let parts: [UIView] = symbol == nil ? [progress, heading, detail] : [icon, heading, detail]
        if symbol == nil { progress.startAnimating() } else { progress.stopAnimating() }
        let stack = ShareStyle.stack(parts, spacing: 10)
        stack.alignment = .center
        let container = UIView()
        stack.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: container.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -24)
        ])
        table.backgroundView = container
    }

    func numberOfSections(in tableView: UITableView) -> Int { sections.count }
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { sections[section].items.count }
    func tableView(_ tableView: UITableView, viewForHeaderInSection section: Int) -> UIView? {
        let label = ShareStyle.label(12, weight: .semibold, color: .secondaryLabel)
        label.text = sections[section].title
        let count = ShareStyle.label(12, color: .tertiaryLabel)
        count.text = "\(sections[section].items.count)"
        let row = ShareStyle.stack([label, count], axis: .horizontal, spacing: 8)
        let wrapper = UIView()
        wrapper.backgroundColor = ShareStyle.canvas
        row.translatesAutoresizingMaskIntoConstraints = false
        wrapper.addSubview(row)
        NSLayoutConstraint.activate([
            row.topAnchor.constraint(equalTo: wrapper.topAnchor, constant: 16), row.bottomAnchor.constraint(equalTo: wrapper.bottomAnchor, constant: -8),
            row.leadingAnchor.constraint(equalTo: wrapper.leadingAnchor, constant: 12), row.trailingAnchor.constraint(lessThanOrEqualTo: wrapper.trailingAnchor, constant: -12)
        ])
        return wrapper
    }
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "destination", for: indexPath) as! ShareDestinationCell
        let destination = sections[indexPath.section].items[indexPath.row]
        cell.configure(destination, selected: selected?.id == destination.id)
        return cell
    }
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        guard !sending && !attempted else { return }
        selected = sections[indexPath.section].items[indexPath.row]
        selectionTitle.text = selected?.name
        selectionDetail.text = selected?.detail
        updateSendButton()
        table.reloadData()
        UISelectionFeedbackGenerator().selectionChanged()
        view.endEditing(true)
    }

    @objc private func send() {
        if loadFailed {
            work = Task { [weak self] in await self?.prepare() }
            return
        }
        guard !sending, let selected = selected, let content = content, let api = api else { return }
        guard !text.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !content.files.isEmpty else { return }
        // Freeze body and destination so a retry uses the same idempotency key.
        attempted = true
        sending = true
        text.isEditable = false
        status.isHidden = true
        view.endEditing(true)
        updateSendButton()
        work = Task { [weak self] in
            guard let self = self else { return }
            do {
                guard ShareSession.load() == api.auth else { throw ShareError.message("Your active account changed. Cancel and share again.") }
                while self.uploaded.count < content.files.count {
                    self.selectionDetail.text = "Uploading \(self.uploaded.count + 1) of \(content.files.count)…"
                    let id = try await api.upload(content.files[self.uploaded.count], channel: selected.id, directory: content.directory)
                    self.uploaded.append(id)
                }
                guard ShareSession.load() == api.auth else { throw ShareError.message("Your active account changed. Cancel and share again.") }
                self.selectionDetail.text = "Sending your share…"
                try await api.send(channel: selected.id, text: self.text.text, attachments: self.uploaded, clientID: self.clientID)
                self.finished = true
                api.cancel()
                self.cleanup()
                self.extensionContext?.completeRequest(returningItems: nil)
            } catch {
                guard !Task.isCancelled else { return }
                self.sending = false
                self.status.text = error.localizedDescription
                self.status.isHidden = false
                self.selectionDetail.text = "Couldn't send. Your share is ready to retry."
                self.updateSendButton()
                UIAccessibility.post(notification: .announcement, argument: error.localizedDescription)
            }
        }
    }

    @objc private func cancel() {
        work?.cancel()
        api?.cancel()
        cleanup()
        extensionContext?.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
    }

    private func cleanup() {
        if let content = content { try? FileManager.default.removeItem(at: content.directory) }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if !finished && (isBeingDismissed || navigationController?.isBeingDismissed == true) { cancel() }
    }
}

private final class ShareDestinationCell: UITableViewCell {
    private let card = UIView()
    private let avatar = UILabel()
    private let name = ShareStyle.label(15, weight: .semibold)
    private let detail = ShareStyle.label(12, color: .secondaryLabel)
    private let check = UIImageView()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none
        card.layer.cornerRadius = 13
        card.layer.cornerCurve = .continuous
        avatar.textAlignment = .center
        avatar.font = .systemFont(ofSize: 15, weight: .semibold)
        avatar.layer.cornerRadius = 12
        avatar.clipsToBounds = true
        avatar.widthAnchor.constraint(equalToConstant: 42).isActive = true
        avatar.heightAnchor.constraint(equalToConstant: 42).isActive = true
        detail.lineBreakMode = .byTruncatingTail
        let labels = ShareStyle.stack([name, detail], spacing: 4)
        labels.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        check.contentMode = .scaleAspectFit
        check.widthAnchor.constraint(equalToConstant: 22).isActive = true
        check.heightAnchor.constraint(equalToConstant: 22).isActive = true
        let row = ShareStyle.stack([avatar, labels, check], axis: .horizontal, spacing: 12)
        row.alignment = .center
        ShareStyle.pin(row, in: card, inset: 12)
        ShareStyle.pin(card, in: contentView, inset: 3)
        isAccessibilityElement = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(_ destination: ShareDestination, selected: Bool) {
        let isDM = destination.kind == .directMessage
        name.text = isDM ? destination.name : destination.name.replacingOccurrences(of: "#", with: "", range: destination.name.range(of: "#"))
        detail.text = destination.subtitle.isEmpty ? destination.detail : (isDM && destination.subtitle != "Group conversation" ? "@" + destination.subtitle : destination.subtitle)
        let colors: [UIColor] = [.systemIndigo, .systemTeal, .systemPurple, .systemOrange, .systemPink]
        let index = destination.id.utf8.reduce(0) { ($0 + Int($1)) % colors.count }
        let color = isDM ? colors[index] : ShareStyle.accent
        avatar.backgroundColor = color.withAlphaComponent(0.12)
        avatar.textColor = color
        avatar.text = isDM ? destination.name.split(separator: " ").prefix(2).compactMap(\.first).map(String.init).joined().uppercased() : "#"
        card.backgroundColor = selected ? ShareStyle.selected : .clear
        check.image = UIImage(systemName: selected ? "checkmark.circle.fill" : "circle", withConfiguration: UIImage.SymbolConfiguration(pointSize: 20, weight: .regular))
        check.tintColor = selected ? ShareStyle.accent : .quaternaryLabel
        accessibilityLabel = destination.name + ", " + destination.detail
        accessibilityTraits = selected ? [.button, .selected] : [.button]
    }
}
