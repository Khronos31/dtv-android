#include "t1_state_machine.h"

#include <cstdint>
#include <cstdlib>
#include <functional>
#include <iostream>
#include <vector>

namespace {

using Bytes = std::vector<std::uint8_t>;

std::uint8_t lrc(const Bytes& bytes) {
    std::uint8_t value = 0;
    for (const auto byte : bytes) value ^= byte;
    return value;
}

Bytes block(std::uint8_t pcb, Bytes inf = {}) {
    Bytes result{0, pcb, static_cast<std::uint8_t>(inf.size())};
    result.insert(result.end(), inf.begin(), inf.end());
    result.push_back(lrc(result));
    return result;
}

struct FakeTransport {
    std::vector<Bytes> requests;
    std::function<Bytes(const Bytes&)> respond;

    static int exchange(void* opaque, const std::uint8_t* request, int request_length,
                        std::uint8_t* response, int response_capacity) {
        auto* self = static_cast<FakeTransport*>(opaque);
        Bytes outgoing(request, request + request_length);
        self->requests.push_back(outgoing);
        const Bytes incoming = self->respond(outgoing);
        if (static_cast<int>(incoming.size()) > response_capacity) return -1;
        for (std::size_t i = 0; i < incoming.size(); ++i) response[i] = incoming[i];
        return static_cast<int>(incoming.size());
    }
};

[[noreturn]] void fail(const char* name) {
    std::cerr << "failed: " << name << '\n';
    std::exit(EXIT_FAILURE);
}

void expect(bool condition, const char* name) {
    if (!condition) fail(name);
}

std::uint8_t pcb(const Bytes& request) {
    expect(request.size() >= 4, "request has a T=1 block");
    return request[1];
}

void test_normal_and_card_chaining() {
    {
        FakeTransport transport;
        transport.respond = [](const Bytes& request) {
            expect(pcb(request) == 0, "normal request N(S)=0");
            return block(0, {0xaa, 0xbb});
        };
        const Bytes apdu{1, 2, 3};
        std::uint8_t send = 0;
        std::uint8_t receive = 0;
        std::uint8_t response[8] = {};
        const int length = t1_sm_transmit(apdu.data(), static_cast<int>(apdu.size()), response,
                                          sizeof(response), &send, &receive, 32,
                                          FakeTransport::exchange, &transport);
        expect(length == 2 && response[0] == 0xaa && response[1] == 0xbb,
               "normal response");
        expect(send == 1 && receive == 1, "normal sequence update");
    }

    {
        FakeTransport transport;
        transport.respond = [&](const Bytes& request) {
            if (transport.requests.size() == 1) {
                expect(pcb(request) == 0x20, "host chaining first I-block");
                return block(0x90);
            }
            if (transport.requests.size() == 2) {
                expect(pcb(request) == 0x60, "host chaining second I-block");
                return block(0x80);
            }
            expect(pcb(request) == 0, "host chaining final I-block");
            return block(0, {0x42});
        };
        const Bytes apdu{1, 2, 3, 4, 5};
        std::uint8_t send = 0;
        std::uint8_t receive = 0;
        std::uint8_t response[8] = {};
        const int length = t1_sm_transmit(apdu.data(), static_cast<int>(apdu.size()), response,
                                          sizeof(response), &send, &receive, 2,
                                          FakeTransport::exchange, &transport);
        expect(length == 1 && response[0] == 0x42, "host chaining response");
        expect(send == 1 && receive == 1, "host chaining sequence update");
    }

    {
        FakeTransport transport;
        transport.respond = [&](const Bytes& request) {
            if (transport.requests.size() == 1) {
                expect(pcb(request) == 0, "card chaining request");
                return block(0x20, {0x10});
            }
            expect(pcb(request) == 0x90, "card chaining acknowledgement");
            return block(0x40, {0x11});
        };
        const Bytes apdu{1};
        std::uint8_t send = 0;
        std::uint8_t receive = 0;
        std::uint8_t response[8] = {};
        const int length = t1_sm_transmit(apdu.data(), static_cast<int>(apdu.size()), response,
                                          sizeof(response), &send, &receive, 32,
                                          FakeTransport::exchange, &transport);
        expect(length == 2 && response[0] == 0x10 && response[1] == 0x11,
               "card chaining response");
        expect(send == 1 && receive == 0, "card chaining sequence update");
    }
}

void test_error_retransmission_and_resynch() {
    FakeTransport transport;
    transport.respond = [&](const Bytes& request) {
        const std::size_t call = transport.requests.size();
        if (call <= 4) {
            expect(pcb(request) == 0, "error retry keeps N(S)");
            return block(0x81);
        }
        if (call == 5) {
            expect(pcb(request) == 0xc0, "resynch request");
            return block(0xe0);
        }
        expect(call == 6 && pcb(request) == 0, "post-resynch I-block reset");
        return block(0, {0x55});
    };
    const Bytes apdu{9, 8, 7};
    std::uint8_t send = 0;
    std::uint8_t receive = 0;
    std::uint8_t response[8] = {};
    const int length = t1_sm_transmit(apdu.data(), static_cast<int>(apdu.size()), response,
                                      sizeof(response), &send, &receive, 32,
                                      FakeTransport::exchange, &transport);
    expect(length == 1 && response[0] == 0x55, "recovered response");
    expect(transport.requests.size() == 6, "three bounded retransmissions");
    for (std::size_t i = 1; i < 4; ++i) {
        expect(transport.requests[i] == transport.requests[0], "byte-identical retransmission");
    }
    expect(send == 1 && receive == 1, "recovered sequence update");
}

void test_resynch_resets_nonzero_sequence() {
    FakeTransport transport;
    transport.respond = [&](const Bytes& request) {
        const std::size_t call = transport.requests.size();
        if (call <= 4) {
            expect(pcb(request) == 0x40, "nonzero sequence error retry");
            return block(0x91);
        }
        if (call == 5) return block(0xe0);
        expect(call == 6 && pcb(request) == 0, "resynch clears N(S)");
        return block(0, {0x66});
    };
    const Bytes apdu{3};
    std::uint8_t send = 1;
    std::uint8_t receive = 1;
    std::uint8_t response[4] = {};
    const int length = t1_sm_transmit(apdu.data(), static_cast<int>(apdu.size()), response,
                                      sizeof(response), &send, &receive, 32,
                                      FakeTransport::exchange, &transport);
    expect(length == 1 && response[0] == 0x66, "nonzero sequence recovered");
    expect(send == 1 && receive == 1, "nonzero sequence committed after recovery");
}

void test_terminal_failure_and_unexpected_sequence() {
    {
        FakeTransport transport;
        transport.respond = [&](const Bytes&) {
            const std::size_t call = transport.requests.size();
            if (call <= 4) return block(0x81);
            if (call == 5) return block(0xe0);
            expect(call <= 9, "terminal retry bound");
            return block(0x81);
        };
        const Bytes apdu{4};
        std::uint8_t send = 0;
        std::uint8_t receive = 0;
        std::uint8_t response[4] = {};
        const int length = t1_sm_transmit(apdu.data(), static_cast<int>(apdu.size()), response,
                                          sizeof(response), &send, &receive, 32,
                                          FakeTransport::exchange, &transport);
        expect(length < 0 && transport.requests.size() == 9, "terminal error is bounded");
        expect(send == 0 && receive == 0, "failed exchange does not commit state");
        expect(transport.requests[4][1] == 0xc0, "one resynch only");
    }

    {
        FakeTransport transport;
        transport.respond = [](const Bytes&) { return block(0x10); };
        const Bytes apdu{1};
        std::uint8_t send = 0;
        std::uint8_t receive = 0;
        std::uint8_t response[4] = {};
        expect(t1_sm_transmit(apdu.data(), 1, response, sizeof(response), &send, &receive, 32,
                              FakeTransport::exchange, &transport) < 0,
               "unexpected response sequence fails closed");
        expect(send == 0 && receive == 0, "unexpected sequence does not commit state");
    }
}

void test_malformed_response_and_overflow_fail_closed() {
    {
        FakeTransport transport;
        transport.respond = [](const Bytes&) {
            Bytes malformed = block(0, {0x12});
            malformed.back() ^= 0x01;
            return malformed;
        };
        const Bytes apdu{1};
        std::uint8_t send = 1;
        std::uint8_t receive = 1;
        std::uint8_t response[4] = {};
        expect(t1_sm_transmit(apdu.data(), 1, response, sizeof(response), &send, &receive, 32,
                              FakeTransport::exchange, &transport) < 0,
               "malformed LRC fails closed");
        expect(send == 1 && receive == 1, "malformed LRC does not commit state");
    }

    {
        FakeTransport transport;
        transport.respond = [](const Bytes&) { return block(0x40, {0x12, 0x34}); };
        const Bytes apdu{1};
        std::uint8_t send = 1;
        std::uint8_t receive = 1;
        std::uint8_t response[1] = {};
        expect(t1_sm_transmit(apdu.data(), 1, response, sizeof(response), &send, &receive, 32,
                              FakeTransport::exchange, &transport) < 0,
               "response overflow fails closed");
        expect(send == 1 && receive == 1, "response overflow does not commit state");
    }

    {
        FakeTransport transport;
        transport.respond = [](const Bytes&) { return Bytes{0, 0, 0}; };
        const Bytes apdu{1};
        std::uint8_t send = 1;
        std::uint8_t receive = 1;
        std::uint8_t response[4] = {};
        expect(t1_sm_transmit(apdu.data(), 1, response, sizeof(response), &send, &receive, 32,
                              FakeTransport::exchange, &transport) < 0,
               "truncated block fails closed");
        expect(send == 1 && receive == 1, "truncated block does not commit state");
    }
}

void test_invalid_resynch_response_fails_before_retry() {
    FakeTransport transport;
    transport.respond = [&](const Bytes& request) {
        const std::size_t call = transport.requests.size();
        if (call <= 4) {
            expect(pcb(request) == 0x40, "invalid resynch retry sequence");
            return block(0x91);
        }
        expect(call == 5 && pcb(request) == 0xc0, "invalid resynch request");
        return block(0xe1);
    };
    const Bytes apdu{1};
    std::uint8_t send = 1;
    std::uint8_t receive = 1;
    std::uint8_t response[4] = {};
    expect(t1_sm_transmit(apdu.data(), 1, response, sizeof(response), &send, &receive, 32,
                          FakeTransport::exchange, &transport) < 0,
           "invalid resynch response fails closed");
    expect(transport.requests.size() == 5, "invalid resynch does not retry I-block");
    expect(send == 1 && receive == 1, "invalid resynch does not commit state");
}

}  // namespace

int main() {
    test_normal_and_card_chaining();
    test_error_retransmission_and_resynch();
    test_resynch_resets_nonzero_sequence();
    test_terminal_failure_and_unexpected_sequence();
    test_malformed_response_and_overflow_fail_closed();
    test_invalid_resynch_response_fails_before_retry();
    std::cout << "t1 state machine tests: PASS\n";
    return EXIT_SUCCESS;
}
