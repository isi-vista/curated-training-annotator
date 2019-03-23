package edu.isi.vista.gigawordIndexer;

import com.google.common.io.ByteSource;
import java.io.IOException;
import java.io.InputStream;

public class ZipEntryByteSource extends ByteSource {

  private InputStream is;

  public ZipEntryByteSource(InputStream is) {
    this.is = is;
  }

  @Override
  public InputStream openStream() throws IOException {
    return is;
  }
}
