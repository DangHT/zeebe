/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.broker.logstreams.processor;

import java.io.InputStream;
import java.io.OutputStream;

import io.zeebe.hashindex.HashIndex;
import io.zeebe.hashindex.IndexSerializer;
import io.zeebe.logstreams.spi.SnapshotSupport;

public class HashIndexSnapshotSupport<T extends HashIndex<?, ?>> implements SnapshotSupport
{
    private final T hashIndex;

    private final IndexSerializer indexSerializer = new IndexSerializer();

    public HashIndexSnapshotSupport(T hashIndex)
    {
        this.hashIndex = hashIndex;
        this.indexSerializer.wrap(hashIndex);
    }

    public T getHashIndex()
    {
        return hashIndex;
    }

    @Override
    public void writeSnapshot(OutputStream outputStream) throws Exception
    {
        indexSerializer.writeToStream(outputStream);
    }

    @Override
    public void recoverFromSnapshot(InputStream inputStream) throws Exception
    {
        indexSerializer.readFromStream(inputStream);
    }

    @Override
    public void reset()
    {
        hashIndex.clear();
    }

}