import SwiftUI
import WidgetKit
import ActivityKit

/// The next prayer on the Lock Screen (navy and gold, its name in Kufi) and in the Dynamic Island.
struct NextLive: Widget {
    private let gold = Color.hex(0xE9CD8A), cream = Color.hex(0xF3E6C4)
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: NextPrayerAttributes.self) { ctx in
            let ar = ctx.attributes.ar, st = ctx.state, nf = (WFont(rawValue: st.font) ?? .kufi).ps
            let line = [st.showTime ? st.clock : "", st.showCity ? ctx.attributes.city : ""].filter { !$0.isEmpty }.joined(separator: " · ")
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(ar ? "الصلاة القادمة" : "Next prayer").font(.custom(WFonts.plex, size: 12)).foregroundStyle(cream.opacity(0.7))
                    Text(st.name).font(.custom(nf, size: 30)).foregroundStyle(cream).lineLimit(1).minimumScaleFactor(0.6)
                    if !line.isEmpty { Text(line).font(.custom(WFonts.plex, size: 12)).foregroundStyle(gold).lineLimit(1) }
                }
                Spacer(minLength: 6)
                if ctx.isStale {
                    Text(ar ? "حان الوقت" : "It's time").font(.custom(WFonts.plexBold, size: 20)).foregroundStyle(gold)
                } else if !st.showCount {
                    Text(st.clock).font(.system(size: 30, weight: .semibold)).monospacedDigit().foregroundStyle(gold)
                } else {
                    Text(timerInterval: Date.now...max(Date.now, ctx.state.at), countsDown: true)
                        .font(.system(size: 30, weight: .semibold)).monospacedDigit().foregroundStyle(gold)
                        .multilineTextAlignment(.trailing).frame(maxWidth: 140)
                }
            }
            .padding(16)
            .environment(\.layoutDirection, ar ? .rightToLeft : .leftToRight)
            .activityBackgroundTint(Color.hex(st.theme == "green" ? 0x0F3B2E : st.theme == "dark" ? 0x101010 : 0x14223F))
            .activitySystemActionForegroundColor(cream)
        } dynamicIsland: { ctx in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Text(ctx.state.name).font(.custom((WFont(rawValue: ctx.state.font) ?? .kufi).ps, size: 22)).foregroundStyle(cream).lineLimit(1).minimumScaleFactor(0.6)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(timerInterval: Date.now...max(Date.now, ctx.state.at), countsDown: true).monospacedDigit().foregroundStyle(gold).frame(maxWidth: 90)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(ctx.state.clock).font(.custom(WFonts.plex, size: 13)).foregroundStyle(cream.opacity(0.75))
                }
            } compactLeading: {
                Text(ctx.state.name).font(.custom((WFont(rawValue: ctx.state.font) ?? .kufi).ps, size: 14)).foregroundStyle(cream).lineLimit(1)
            } compactTrailing: {
                Text(timerInterval: Date.now...max(Date.now, ctx.state.at), countsDown: true).monospacedDigit().foregroundStyle(gold).frame(maxWidth: 52)
            } minimal: {
                Image(systemName: "moon.stars.fill").foregroundStyle(gold)
            }
        }
    }
}
