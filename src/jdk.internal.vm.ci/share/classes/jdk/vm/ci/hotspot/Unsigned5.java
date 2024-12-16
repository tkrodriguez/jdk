/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

package jdk.vm.ci.hotspot;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class Unsigned5 {
    public static final int LogBitsPerByte = 3;
    public static final int BitsPerByte = 1 << 3;

    // Constants for UNSIGNED5 coding of Pack200
    private static final int lg_H = 6;     // log-base-2 of H (lg 64 == 6)
    private static final int H = 1 << lg_H;  // number of "high" bytes (64)
    private static final int X = 1;  // there is one excluded byte ('\0')
    private static final int MAX_b = (1 << BitsPerByte) - 1;  // largest byte value
    private static final int L = (MAX_b + 1) - X - H;  // number of "low" bytes (191)
    public static final int MAX_LENGTH = 5;  // lengths are in [1..5]

    // UNSIGNED5::read_uint(_buffer, &_position, limit=0)
    // In C++ this is a generic algorithm, templated with "holes"
    // for array (ARR), offset (OFF), and fetch behavior (GET).
    // In addition, the position is updated by reference.
    // Let us mimic these conditions with two lambdas, both
    // on the ARR parameter.  We will hardwire the position
    // type (OFF) to int (sorry, not long), and omit the extra
    // limit feature.
    public static long readUint(DataInputStream base) throws IOException {
        int b_0 = base.readByte() & 0xff;
        int sum = b_0 - X;
        // VM throws assert if b0<X; we just return -1 here instead
        if (sum < L) {  // common case
            return Integer.toUnsignedLong(sum);
        }
        // must collect more bytes:  b[1]...b[4]
        int lg_H_i = lg_H;  // lg(H)*i == lg(H^^i)
        for (int i = 1; ; i++) {  // for i in [1..4]
            int b_i = base.readByte() & 0xff;
            if (b_i < X) {  // avoid excluded bytes
                throw new InternalError("bad byte: " + b_i);
            }
            sum += (b_i - X) << lg_H_i;  // sum += (b[i]-X)*(64^^i)
            if (b_i < X + L || i == MAX_LENGTH - 1) {
                return Integer.toUnsignedLong(sum);
            }
            lg_H_i += lg_H;
        }
    }

    public static void writeUint(int value, DataOutputStream array) throws IOException {
        if (value < L) {
            int b_0 = X + value;
            assert b_0 == (b_0 & 0xff) : "valid byte";
            array.writeByte(b_0);
            return;
        }
        int sum = value;
        for (int i = 0; ; i++) {  // for i in [0..4]
            if (sum < L || i == MAX_LENGTH - 1) {
                // remainder is either a "low code" or the 5th byte
                int b_i = X + sum;
                assert b_i == (b_i & 0xff) : "valid byte";
                array.writeByte((byte) b_i);
                return;
            }
            sum -= L;
            int b_i = X + L + (sum % H);  // this is a "high code"
            assert b_i == (b_i & 0xff) : "valid byte";
            array.writeByte((byte) b_i);
            sum >>= lg_H;                 // extracted 6 bits
        }
    }

}
