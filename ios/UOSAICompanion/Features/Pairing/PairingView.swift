import SwiftUI

struct PairingView: View {
    @ObservedObject var model: AppModel
    @State private var invitation = ""
    @State private var deviceName = UIDevice.current.name

    var body: some View {
        Form {
            Section("UOS AI Companion") {
                Text("Connect only through your Tailscale private network.")
                    .font(.footnote)
            }

            Section("Pair this iPhone") {
                TextField("Pairing invitation", text: $invitation, axis: .vertical)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .lineLimit(3...6)
                TextField("Device name", text: $deviceName)
                Button("Pair") {
                    Task { await model.pair(pastedInvitation: invitation, deviceName: deviceName) }
                }
                .disabled(invitation.isEmpty || deviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }

            if let host = model.pairedHostName {
                Section("Connection") {
                    Text("Paired with \(host)")
                    Text(model.connectionState.rawValue)
                        .foregroundStyle(.secondary)
                    Button("Reconnect") { Task { await model.reconnect() } }
                }
            }

            if let error = model.errorMessage {
                Section {
                    Text(error).foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("UOS AI")
    }
}
