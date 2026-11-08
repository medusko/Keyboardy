import AppKit
import Combine
import KeyboardyCore
import SwiftUI

final class FloatingHudPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
}

@MainActor
final class KeyboardyAppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem!
    private let popover = NSPopover()
    private var floatingPanel: FloatingHudPanel?

    private let prefs = KeyboardyPrefs.shared
    private let viewModel = KeyboardyViewModel()
    private var cancellables = Set<AnyCancellable>()
    private var localKeyMonitor: Any?
    private var globalClickOutsideMonitor: Any?

    private var lastExternalApp: NSRunningApplication?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Run as an accessory/menu-bar app (no clutter in Dock, stays in Menu Bar like GIF)
        NSApp.setActivationPolicy(.accessory)
        setupMainMenuForStandardTextEditing()

        captureCurrentFrontmostExternalApp()
        observeWorkspaceActiveApplications()

        setupStatusBarItem()
        setupPopoverAndFloatingPanel()
        setupBindingsAndTriggers()
        setupKeyboardShortcutsInsidePanel()
        checkAndPromptInitialPermissions()
        prefs.applyAppLauncherIcon(prefs.appLauncherIcon)
    }

    private func checkAndPromptInitialPermissions() {
        let isTrusted = ImageCommitService.shared.isAccessibilityTrusted(promptIfNeeded: false)
        prefs.isAccessibilityGranted = isTrusted
    }

    /// Provides a standard macOS Edit menu so ⌘A (Select All), ⌘C, ⌘V, ⌘X, and ⌘Z work natively inside the Search TextField.
    private func setupMainMenuForStandardTextEditing() {
        let mainMenu = NSMenu()

        let appMenuItem = NSMenuItem()
        mainMenu.addItem(appMenuItem)
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "Quit Keyboardy", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appMenuItem.submenu = appMenu

        let editMenuItem = NSMenuItem()
        mainMenu.addItem(editMenuItem)
        let editMenu = NSMenu(title: "Edit")
        editMenu.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        let redoItem = editMenu.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "Z")
        redoItem.keyEquivalentModifierMask = [.command, .shift]
        editMenu.addItem(.separator())
        editMenu.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        editMenu.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        editMenu.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        editMenu.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editMenuItem.submenu = editMenu

        NSApp.mainMenu = mainMenu
    }

    private func captureCurrentFrontmostExternalApp() {
        let myPid = ProcessInfo.processInfo.processIdentifier
        if let front = NSWorkspace.shared.frontmostApplication,
           front.processIdentifier != myPid {
            lastExternalApp = front
            return
        }
        if lastExternalApp == nil || lastExternalApp?.isTerminated == true {
            lastExternalApp = NSWorkspace.shared.runningApplications.first(where: {
                $0.processIdentifier != myPid &&
                $0.activationPolicy == .regular &&
                !$0.isTerminated
            })
        }
    }

    private func observeWorkspaceActiveApplications() {
        let myPid = ProcessInfo.processInfo.processIdentifier
        let center = NSWorkspace.shared.notificationCenter

        center.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification,
            object: nil,
            queue: .main
        ) { [weak self] note in
            guard let app = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication,
                  app.processIdentifier != myPid else {
                return
            }
            Task { @MainActor in
                self?.lastExternalApp = app
            }
        }

        // Also capture the app that just deactivated when the user clicks Keyboardy's menu bar icon!
        center.addObserver(
            forName: NSWorkspace.didDeactivateApplicationNotification,
            object: nil,
            queue: .main
        ) { [weak self] note in
            guard let app = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication,
                  app.processIdentifier != myPid else {
                return
            }
            Task { @MainActor in
                self?.lastExternalApp = app
            }
        }
    }

    private func setupStatusBarItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        updateStatusBarBadge(style: prefs.statusBarBadgeStyle)

        if let button = statusItem.button {
            button.target = self
            button.action = #selector(onStatusBarClicked(_:))
            button.sendAction(on: [.leftMouseUp, .rightMouseUp])
            button.toolTip = "Keyboardy \(KeyboardyVersion.displayString) — Search & Insert Google Images & GIFs (\(prefs.hotkeyPreset.shortLabel))"
        }
    }

    private func updateStatusBarBadge(style: StatusBarBadgeStyle) {
        guard let button = statusItem?.button else { return }
        button.image = StatusBarBadgeRenderer.makeStatusBarImage(style: style)
        button.imagePosition = .imageOnly
    }

    private func setupPopoverAndFloatingPanel() {
        let rootView = KeyboardyPickerView(viewModel: viewModel, prefs: prefs)
        let hostingController = NSHostingController(rootView: rootView)

        popover.contentSize = NSSize(width: 625, height: 688)
        popover.behavior = .transient
        popover.animates = true
        popover.contentViewController = hostingController

        let panelView = KeyboardyPickerView(viewModel: viewModel, prefs: prefs)
        let panelHosting = NSHostingController(rootView: panelView)
        let panel = FloatingHudPanel(
            contentRect: NSRect(x: 0, y: 0, width: 625, height: 688),
            styleMask: [.titled, .fullSizeContentView, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.isFloatingPanel = true
        panel.level = .statusBar
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.isMovableByWindowBackground = true
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = true
        panel.contentViewController = panelHosting
        self.floatingPanel = panel

        viewModel.onRequestClosePanel = { [weak self] in
            self?.closeAllPanels(restoreFocusToTarget: true)
        }
    }

    private func setupBindingsAndTriggers() {
        prefs.$isDarkMode
            .receive(on: RunLoop.main)
            .sink { [weak self] isDark in
                let appearance = NSAppearance(named: isDark ? .darkAqua : .aqua)
                self?.popover.appearance = appearance
                self?.floatingPanel?.appearance = appearance
            }
            .store(in: &cancellables)

        prefs.$statusBarBadgeStyle
            .receive(on: RunLoop.main)
            .sink { [weak self] newStyle in
                self?.updateStatusBarBadge(style: newStyle)
            }
            .store(in: &cancellables)

        prefs.$hotkeyPreset
            .combineLatest(prefs.$customKeyCode, prefs.$customCarbonModifiers)
            .receive(on: RunLoop.main)
            .sink { [weak self] _, _, _ in
                TriggerManager.shared.applyCurrentHotkeyFromPrefs()
                let label = self?.prefs.activeHotkeyShortLabel ?? "⌥⇧Space"
                self?.statusItem?.button?.toolTip = "Keyboardy \(KeyboardyVersion.displayString) — Search & Insert Google Images & GIFs (\(label))"
            }
            .store(in: &cancellables)

        TriggerManager.shared.onHotkeyTriggered = { [weak self] in
            self?.toggleFromTrigger(initialQueryOverride: nil)
        }

        TriggerManager.shared.onInlinePrefixTriggered = { [weak self] query in
            self?.toggleFromTrigger(initialQueryOverride: query)
        }
    }

    @objc private func onStatusBarClicked(_ sender: NSStatusBarButton) {
        guard let event = NSApp.currentEvent else {
            togglePopoverAnchoredToStatusBar(initialQueryOverride: nil)
            return
        }

        if event.type == .rightMouseUp || event.modifierFlags.contains(.option) {
            showStatusBarContextMenu()
        } else {
            togglePopoverAnchoredToStatusBar(initialQueryOverride: nil)
        }
    }

    private func showStatusBarContextMenu() {
        let menu = NSMenu()
        let openItem = NSMenuItem(
            title: "Search Google Images & GIFs (\(prefs.activeHotkeyShortLabel))",
            action: #selector(menuOpenSearch),
            keyEquivalent: ""
        )
        openItem.target = self
        menu.addItem(openItem)

        let settingsItem = NSMenuItem(
            title: "Trigger & Status Bar Settings...",
            action: #selector(menuOpenSettings),
            keyEquivalent: ","
        )
        settingsItem.target = self
        menu.addItem(settingsItem)

        menu.addItem(.separator())

        let quitItem = NSMenuItem(
            title: "Quit Keyboardy",
            action: #selector(menuQuit),
            keyEquivalent: "q"
        )
        quitItem.target = self
        menu.addItem(quitItem)

        statusItem.menu = menu
        statusItem.button?.performClick(nil)
        statusItem.menu = nil
    }

    @objc private func menuOpenSearch() {
        togglePopoverAnchoredToStatusBar(initialQueryOverride: nil)
    }

    @objc private func menuOpenSettings() {
        if !isAnyPanelVisible {
            togglePopoverAnchoredToStatusBar(initialQueryOverride: nil)
        }
        viewModel.isShowingSettings = true
    }

    @objc private func menuQuit() {
        NSApp.terminate(nil)
    }

    private var isAnyPanelVisible: Bool {
        popover.isShown || (floatingPanel?.isVisible == true)
    }

    private func toggleFromTrigger(initialQueryOverride: String?) {
        if isAnyPanelVisible {
            closeAllPanels(restoreFocusToTarget: true)
            return
        }

        switch prefs.popupAnchorMode {
        case .statusBarPopover:
            togglePopoverAnchoredToStatusBar(initialQueryOverride: initialQueryOverride)
        case .floatingCenterHud:
            showFloatingHud(nearCursor: false, initialQueryOverride: initialQueryOverride)
        case .nearMouseCursor:
            showFloatingHud(nearCursor: true, initialQueryOverride: initialQueryOverride)
        }
    }

    private func togglePopoverAnchoredToStatusBar(initialQueryOverride: String?) {
        if popover.isShown {
            closeAllPanels(restoreFocusToTarget: true)
            return
        }
        floatingPanel?.orderOut(nil)
        captureCurrentFrontmostExternalApp()
        prefs.isAccessibilityGranted = ImageCommitService.shared.isAccessibilityTrusted(promptIfNeeded: false)
        viewModel.prepareForPanelOpen(capturedApp: lastExternalApp, initialQueryOverride: initialQueryOverride)

        guard let button = statusItem.button else {
            showFloatingHud(nearCursor: false, initialQueryOverride: initialQueryOverride)
            return
        }

        NSApp.activate(ignoringOtherApps: true)
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
        popover.contentViewController?.view.window?.makeKey()
    }

    private func showFloatingHud(nearCursor: Bool, initialQueryOverride: String?) {
        popover.performClose(nil)
        captureCurrentFrontmostExternalApp()
        prefs.isAccessibilityGranted = ImageCommitService.shared.isAccessibilityTrusted(promptIfNeeded: false)
        viewModel.prepareForPanelOpen(capturedApp: lastExternalApp, initialQueryOverride: initialQueryOverride)

        guard let panel = floatingPanel else { return }
        let panelSize = NSSize(width: 500, height: 550)

        if nearCursor {
            let mouseLoc = NSEvent.mouseLocation
            let screen = NSScreen.screens.first(where: { NSMouseInRect(mouseLoc, $0.frame, false) }) ?? NSScreen.main
            let visible = screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
            let x = min(max(mouseLoc.x - panelSize.width / 2, visible.minX + 12), visible.maxX - panelSize.width - 12)
            let y = min(max(mouseLoc.y - panelSize.height - 20, visible.minY + 12), visible.maxY - panelSize.height - 12)
            panel.setFrame(NSRect(origin: NSPoint(x: x, y: y), size: panelSize), display: true)
        } else {
            let screen = NSScreen.main
            let visible = screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
            let x = visible.midX - panelSize.width / 2
            let y = visible.midY - panelSize.height / 2 + 60
            panel.setFrame(NSRect(origin: NSPoint(x: x, y: y), size: panelSize), display: true)
        }

        NSApp.activate(ignoringOtherApps: true)
        panel.makeKeyAndOrderFront(nil)

        if globalClickOutsideMonitor == nil {
            globalClickOutsideMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { [weak self] _ in
                Task { @MainActor in
                    if self?.floatingPanel?.isVisible == true {
                        self?.closeAllPanels(restoreFocusToTarget: false)
                    }
                }
            }
        }
    }

    private func closeAllPanels(restoreFocusToTarget: Bool) {
        if popover.isShown {
            popover.performClose(nil)
        }
        if floatingPanel?.isVisible == true {
            floatingPanel?.orderOut(nil)
        }
        if let monitor = globalClickOutsideMonitor {
            NSEvent.removeMonitor(monitor)
            globalClickOutsideMonitor = nil
        }
        if restoreFocusToTarget {
            NSApp.hide(nil)
            lastExternalApp?.activate(options: [.activateIgnoringOtherApps])
        }
    }

    private func setupKeyboardShortcutsInsidePanel() {
        localKeyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self = self, self.isAnyPanelVisible else { return event }

            // If user clicked "Record Custom Shortcut" in Settings, capture the key combo
            if self.prefs.isRecordingCustomShortcut {
                if event.keyCode == 53 { // Escape cancels recording
                    self.prefs.isRecordingCustomShortcut = false
                    return nil
                }
                if let parsed = TriggerManager.parseShortcutEvent(event) {
                    self.prefs.saveCustomRecordedShortcut(
                        keyCode: parsed.keyCode,
                        carbonModifiers: parsed.carbonModifiers,
                        displayLabel: parsed.label
                    )
                    return nil
                }
                return event
            }

            // Escape closes panel
            if event.keyCode == 53 {
                if self.viewModel.isShowingSettings {
                    self.viewModel.isShowingSettings = false
                } else if self.viewModel.isShowingHistory {
                    self.viewModel.isShowingHistory = false
                } else {
                    self.closeAllPanels(restoreFocusToTarget: true)
                }
                return nil
            }

            // Return / Enter key inside the search screen runs the search (and saves to history) without dismissing the popover
            if (event.keyCode == 36 || event.keyCode == 76) && !self.viewModel.isShowingSettings {
                if event.modifierFlags.contains(.command) {
                    // Cmd + Return inserts the currently highlighted grid item if any
                    if !self.viewModel.results.isEmpty,
                       self.viewModel.results.indices.contains(self.viewModel.selectedIndex) {
                        self.viewModel.selectImageResult(self.viewModel.results[self.viewModel.selectedIndex])
                    }
                } else {
                    self.viewModel.submitSearchFromKeyboard()
                }
                return nil
            }

            // Standard text editing shortcuts inside the search field / settings fields (⌘A, ⌘C, ⌘V, ⌘X, ⌘Z, ⌘⇧Z)
            let mods = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
            if mods == .command, let key = event.charactersIgnoringModifiers?.lowercased() {
                switch key {
                case "a":
                    if NSApp.sendAction(#selector(NSText.selectAll(_:)), to: nil, from: nil) {
                        return nil
                    }
                case "c":
                    if NSApp.sendAction(#selector(NSText.copy(_:)), to: nil, from: nil) {
                        return nil
                    }
                case "v":
                    if NSApp.sendAction(#selector(NSText.paste(_:)), to: nil, from: nil) {
                        return nil
                    }
                case "x":
                    if NSApp.sendAction(#selector(NSText.cut(_:)), to: nil, from: nil) {
                        return nil
                    }
                case "z":
                    if NSApp.sendAction(Selector(("undo:")), to: nil, from: nil) {
                        return nil
                    }
                default:
                    break
                }
            } else if mods == [.command, .shift], event.charactersIgnoringModifiers?.lowercased() == "z" {
                if NSApp.sendAction(Selector(("redo:")), to: nil, from: nil) {
                    return nil
                }
            }

            // Cmd + 1..9 quick-selects image result 0..8
            if event.modifierFlags.contains(.command),
               let chars = event.charactersIgnoringModifiers,
               let digit = Int(chars),
               (1...9).contains(digit) {
                let idx = digit - 1
                if self.viewModel.results.indices.contains(idx) {
                    self.viewModel.selectedIndex = idx
                    self.viewModel.selectImageResult(self.viewModel.results[idx])
                    return nil
                }
            }

            // Cmd + , toggles Settings
            if event.modifierFlags.contains(.command), event.charactersIgnoringModifiers == "," {
                self.viewModel.isShowingSettings.toggle()
                return nil
            }

            // Arrow keys navigate the 3-column grid when results are visible
            if !self.viewModel.isShowingSettings && !self.viewModel.isShowingHistory && !self.viewModel.results.isEmpty {
                switch event.keyCode {
                case 125: // Down arrow
                    self.viewModel.moveSelection(delta: 3)
                    return nil
                case 126: // Up arrow
                    self.viewModel.moveSelection(delta: -3)
                    return nil
                default:
                    break
                }
            }

            return event
        }
    }
}
