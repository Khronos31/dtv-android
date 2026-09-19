#pragma once

#include <cstdint>

namespace px4_adapter {

constexpr unsigned int kCardConnectMaxAttempts = 5;
constexpr unsigned int kCardConnectRetryDelayUs = 400000;
static_assert((kCardConnectMaxAttempts - 1U) * kCardConnectRetryDelayUs <= 2000000U,
              "card connect retry budget must remain modest");

// The callbacks keep the retry policy host-testable without constructing a
// vendor card client. connect() must return a result with bool conversion,
// error(), and value().handle, matching IfdCardClient::connect_shared().
template <typename Connect, typename Retryable, typename Sleep>
bool connect_shared_with_retry(
    Connect connect, Retryable retryable, Sleep sleep, std::uint64_t* handle) {
    if (handle == nullptr) return false;
    for (unsigned int attempt = 0; attempt < kCardConnectMaxAttempts; ++attempt) {
        const auto result = connect();
        if (result) {
            if (result.value().handle != 0) {
                *handle = result.value().handle;
                return true;
            }
            return false;
        }
        if (!retryable(result.error())) {
            return false;
        }
        if (attempt + 1U < kCardConnectMaxAttempts) {
            sleep(kCardConnectRetryDelayUs);
        }
    }
    return false;
}

}  // namespace px4_adapter
