/*
 Copyright (c) 2009 Kristofer Karlsson <kristofer.karlsson@gmail.com>

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 THE SOFTWARE.
 */

package se.krka.kahlua.vm;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public abstract interface KahluaTable {
	public abstract void setMetatable(KahluaTable metatable);

	public abstract KahluaTable getMetatable();

	public abstract void rawset(Object key, Object value);

	public abstract Object rawget(Object key);



	public abstract void rawset(int key, Object value);

	public abstract Object rawget(int key);

	public abstract int len();

	public abstract KahluaTableIterator iterator();

	public abstract boolean isEmpty();

	public abstract void wipe();

	public abstract void save(ByteBuffer paramByteBuffer) throws IOException;

	public abstract void load(ByteBuffer paramByteBuffer, int paramInt)
			throws IOException;

	public abstract void save(DataOutputStream paramDataOutputStream)
			throws IOException;

	public abstract void load(DataInputStream paramDataInputStream, int paramInt)
			throws IOException;

	public abstract String getString(String paramString);

}
