package com.burpworkbench.core.codec;

public record DecodeResult(byte[] bytes, boolean decoded, String note) {
}

