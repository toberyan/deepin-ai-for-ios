import SwiftUI

struct ApprovalSheet: View {
    let approval: AgentApproval
    let respond: (Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Agent approval required").font(.headline)
            Text(approval.title).font(.title3)
            Text(approval.actionType).foregroundStyle(.secondary)
            if !approval.details.isEmpty {
                Text(detailsText).font(.footnote).textSelection(.enabled)
            }
            HStack {
                Button("Reject", role: .destructive) { respond(false) }
                Spacer()
                Button("Allow once") { respond(true) }
            }
        }
        .padding(24)
        .presentationDetents([.medium])
    }

    private var detailsText: String {
        let data = (try? JSONEncoder().encode(approval.details)) ?? Data()
        return String(data: data, encoding: .utf8) ?? ""
    }
}
