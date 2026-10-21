import UIKit

@MainActor
final class ShareViewController: UIViewController, UITableViewDataSource, UITableViewDelegate, UISearchResultsUpdating {
    private let table = UITableView(frame: .zero, style: .insetGrouped)
    private let text = UITextView()
    private let status = UILabel()
    private let search = UISearchController(searchResultsController: nil)
    private var sendButton: UIBarButtonItem!
    private var destinations: [ShareDestination] = []
    private var filtered: [ShareDestination] = []
    private var selected: ShareDestination?
    private var content: ShareContent?
    private var api: ShareAPI?
    private var work: Task<Void, Never>?
    private var uploaded: [String] = []
    private let clientID = UUID().uuidString
    private var sending = false
    private var attempted = false
    private var finished = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let nav = UINavigationBar()
        nav.translatesAutoresizingMaskIntoConstraints = false
        let item = UINavigationItem(title: "Share to Hasht")
        item.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .cancel, target: self, action: #selector(cancel))
        sendButton = UIBarButtonItem(title: "Send", style: .done, target: self, action: #selector(send))
        sendButton.isEnabled = false
        item.rightBarButtonItem = sendButton
        nav.items = [item]
        text.font = .preferredFont(forTextStyle: .body)
        text.isEditable = false
        text.accessibilityLabel = "Shared text"
        status.font = .preferredFont(forTextStyle: .footnote)
        status.textColor = .secondaryLabel
        status.numberOfLines = 3
        status.lineBreakMode = .byTruncatingTail
        status.text = "Reading shared content…"
        table.dataSource = self
        table.delegate = self
        table.keyboardDismissMode = .onDrag
        search.searchResultsUpdater = self
        search.obscuresBackgroundDuringPresentation = false
        search.searchBar.placeholder = "Search DMs and channels"
        table.tableHeaderView = search.searchBar
        definesPresentationContext = true
        let preview = UIStackView(arrangedSubviews: [status, text])
        preview.axis = .vertical
        preview.spacing = 8
        preview.isLayoutMarginsRelativeArrangement = true
        preview.directionalLayoutMargins = .init(top: 12, leading: 16, bottom: 12, trailing: 16)
        for child in [nav, preview, table] {
            child.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(child)
        }
        NSLayoutConstraint.activate([
            nav.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            nav.leadingAnchor.constraint(equalTo: view.leadingAnchor), nav.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            preview.topAnchor.constraint(equalTo: nav.bottomAnchor), preview.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            preview.trailingAnchor.constraint(equalTo: view.trailingAnchor), text.heightAnchor.constraint(equalToConstant: 90),
            table.topAnchor.constraint(equalTo: preview.bottomAnchor), table.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            table.trailingAnchor.constraint(equalTo: view.trailingAnchor), table.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        work = Task { [weak self] in await self?.prepare() }
    }

    private func prepare() async {
        api?.cancel()
        cleanup()
        content = nil
        sendButton.isEnabled = false
        guard let auth = ShareSession.load() else {
            status.text = "Open Hasht and sign in to an instance, then share again."
            return
        }
        let api = ShareAPI(auth: auth)
        self.api = api
        do {
            let content = try await ShareContent.read(extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            guard !Task.isCancelled else { try? FileManager.default.removeItem(at: content.directory); return }
            self.content = content
            text.text = content.text
            text.isEditable = true
            let files = content.files.map(\.name).joined(separator: ", ")
            status.text = "\(auth.username) · \(URL(string: auth.origin)?.host ?? auth.origin)" + (files.isEmpty ? "" : "\n" + files)
            destinations = try await api.destinations()
            updateSearchResults(for: search)
            if destinations.isEmpty { status.text = "No writable DMs or text channels. Open Hasht to join a channel or start a conversation." }
        } catch {
            sendButton.title = "Retry"
            sendButton.isEnabled = true
            show(error)
        }
    }

    func updateSearchResults(for searchController: UISearchController) {
        let query = searchController.searchBar.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        filtered = destinations.filter { query.isEmpty || ($0.name + " " + $0.detail).localizedCaseInsensitiveContains(query) }
        table.reloadData()
        table.backgroundView = filtered.isEmpty && !destinations.isEmpty ? emptyLabel() : nil
    }

    private func emptyLabel() -> UILabel {
        let label = UILabel()
        label.text = "No matching conversations or channels"
        label.textAlignment = .center
        label.textColor = .secondaryLabel
        label.font = .preferredFont(forTextStyle: .body)
        label.adjustsFontSizeToFitWidth = true
        return label
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { filtered.count }
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        let destination = filtered[indexPath.row]
        cell.textLabel?.text = destination.name
        cell.detailTextLabel?.text = destination.detail
        cell.accessoryType = selected?.id == destination.id ? .checkmark : .none
        return cell
    }
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        guard !sending && !attempted else { return }
        selected = filtered[indexPath.row]
        sendButton.title = "Send"
        sendButton.isEnabled = content != nil
        table.reloadData()
        view.endEditing(true)
    }

    @objc private func send() {
        if selected == nil {
            work = Task { [weak self] in await self?.prepare() }
            return
        }
        guard !sending, let selected = selected, let content = content, let api = api else { return }
        guard !text.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !content.files.isEmpty else {
            status.text = "Add some text to share."
            return
        }
        // Keep the destination and body stable after an ambiguous network failure:
        // retrying the same client ID cannot send a second message.
        attempted = true
        sending = true
        text.isEditable = false
        sendButton.isEnabled = false
        search.isActive = false
        work = Task { [weak self] in
            guard let self = self else { return }
            do {
                guard ShareSession.load() == api.auth else { throw ShareError.message("Your active account changed. Cancel and share again.") }
                while self.uploaded.count < content.files.count {
                    self.status.text = "Uploading file \(self.uploaded.count + 1) of \(content.files.count)…"
                    let id = try await api.upload(content.files[self.uploaded.count], channel: selected.id, directory: content.directory)
                    self.uploaded.append(id)
                }
                guard ShareSession.load() == api.auth else { throw ShareError.message("Your active account changed. Cancel and share again.") }
                self.status.text = "Sending to \(selected.name)…"
                try await api.send(channel: selected.id, text: self.text.text, attachments: self.uploaded, clientID: self.clientID)
                self.finished = true
                api.cancel()
                self.cleanup()
                self.extensionContext?.completeRequest(returningItems: nil)
            } catch {
                self.sending = false
                self.sendButton.title = "Retry"
                self.sendButton.isEnabled = true
                self.show(error)
            }
        }
    }

    private func show(_ error: Error) {
        guard !Task.isCancelled else { return }
        status.text = error.localizedDescription
        UIAccessibility.post(notification: .announcement, argument: status.text)
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
        if !finished && !search.isActive && (isBeingDismissed || navigationController?.isBeingDismissed == true) { cancel() }
    }
}
