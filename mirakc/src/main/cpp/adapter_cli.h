#pragma once

#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace mirakc_adapter {

inline bool decimal_token(const std::string& text, unsigned int maximum,
                          unsigned int* value) {
    if (text.empty() || (text.size() > 1 && text.front() == '0')) return false;
    unsigned int parsed = 0;
    for (const char character : text) {
        if (character < '0' || character > '9') return false;
        const unsigned int digit = static_cast<unsigned int>(character - '0');
        if (parsed > (maximum - digit) / 10U) return false;
        parsed = parsed * 10U + digit;
    }
    *value = parsed;
    return true;
}

inline bool receiver_token(const std::string& text, unsigned int* value) {
    return decimal_token(text, 65535U, value);
}

inline bool valid_channel(const std::string& channel, bool have_tsid) {
    unsigned int value = 0;
    const std::string terrestrial = channel.size() > 1 && channel.front() == 'T'
        ? channel.substr(1) : channel;
    if (decimal_token(terrestrial, 62, &value)) {
        return value >= 13U && !have_tsid;
    }
    if (channel.size() >= 4 && channel.compare(0, 2, "BS") == 0) {
        if (channel[2] < '0' || channel[2] > '9' ||
            channel[3] < '0' || channel[3] > '9') return false;
        const unsigned int transponder = static_cast<unsigned int>(
            (channel[2] - '0') * 10 + channel[3] - '0');
        if (transponder < 1U || transponder > 23U || (transponder & 1U) == 0U) return false;
        if (channel.size() == 4) return have_tsid;
        if (channel[4] != '_') return false;
        return decimal_token(channel.substr(5), 11, &value);
    }
    if (channel.size() > 2 && channel.compare(0, 2, "CS") == 0 &&
        decimal_token(channel.substr(2), 24, &value)) {
        return value >= 2U && (value & 1U) == 0U;
    }
    return false;
}

struct NormalizedSelection final {
    std::string channel;
    std::string tsid;
    bool have_tsid = false;
};

inline bool normalize_selection(const std::string& channel,
                                std::string_view requested_tsid,
                                bool requested,
                                NormalizedSelection* selection) {
    if (selection == nullptr) return false;
    NormalizedSelection normalized;
    normalized.channel = channel;
    if (requested) {
        unsigned int value = 0U;
        if (!decimal_token(std::string(requested_tsid), 65535U, &value) ||
            value == 65535U) return false;
        if (value != 0U) {
            normalized.have_tsid = true;
            normalized.tsid.assign(requested_tsid);
            if (normalized.channel.size() > 4 &&
                normalized.channel.compare(0, 2, "BS") == 0 &&
                normalized.channel[4] == '_') normalized.channel.resize(4U);
        }
    }
    if (!valid_channel(normalized.channel, normalized.have_tsid)) return false;
    *selection = std::move(normalized);
    return true;
}

inline std::vector<std::string> build_ts_arguments(
    const std::string& program, const std::string& instance,
    const std::string& receiver, const NormalizedSelection& selection,
    const std::string& runtime_dir) {
    std::vector<std::string> arguments{
        program, "--instance", instance, "--receiver", receiver,
        "--channel", selection.channel};
    if (selection.have_tsid) {
        arguments.emplace_back("--tsid");
        arguments.push_back(selection.tsid);
    }
    arguments.emplace_back("--runtime-dir");
    arguments.push_back(runtime_dir);
    arguments.emplace_back("--output");
    arguments.emplace_back("-");
    return arguments;
}

}  // namespace mirakc_adapter
