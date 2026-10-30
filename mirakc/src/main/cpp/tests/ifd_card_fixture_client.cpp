#include "px4/pcsc_ifd_adapter.h"

#include <memory>
#include <cstdio>
#include <string>

namespace {

using namespace px4::userland::pcsc;

constexpr const char* kInstance = "fixture-one";
PosixIfdCardClientFactory factory;
std::unique_ptr<IfdCardClient> client;
std::uint64_t card_handle = 0U;

}  // namespace

extern "C" void ifd_fixture_close();

extern "C" int ifd_fixture_open(const char* runtime, std::uint64_t* handle)
{
    if (runtime == nullptr || handle == nullptr) return 0;
    ifd_fixture_close();
    IfdEndpoint endpoint{runtime, kInstance, false};
    auto connected = factory.connect(endpoint);
    if (!connected) {
        std::fprintf(stderr, "IFD factory connect failed: %u\n",
                     static_cast<unsigned>(connected.error()));
        return 0;
    }
    client = std::move(connected.value());
    const auto opened = client->connect_shared();
    if (!opened || opened.value().handle == 0U) {
        std::fprintf(stderr, "IFD connect_shared failed: %u\n",
                     opened ? 0U : static_cast<unsigned>(opened.error()));
        client->close();
        client.reset();
        return 0;
    }
    card_handle = opened.value().handle;
    *handle = card_handle;
    return 1;
}

extern "C" int ifd_fixture_transmit(std::uint64_t handle,
                                     const std::uint8_t* apdu,
                                     std::size_t apdu_size,
                                     std::uint8_t* response,
                                     std::size_t* response_size)
{
    if (client == nullptr || response_size == nullptr ||
        (apdu_size != 0U && apdu == nullptr)) return 0;
    const std::size_t capacity = *response_size;
    const auto result = client->transmit(
        handle, {apdu, apdu_size}, {response, capacity});
    if (!result) return 0;
    *response_size = result.value();
    return 1;
}

extern "C" int ifd_fixture_disconnect(std::uint64_t handle)
{
    if (client == nullptr || handle == 0U || handle != card_handle) return 0;
    const auto result = client->disconnect(handle);
    if (!result) return 0;
    card_handle = 0U;
    return 1;
}

extern "C" void ifd_fixture_close()
{
    if (client != nullptr) client->close();
    client.reset();
    card_handle = 0U;
}
