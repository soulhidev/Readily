package com.infmme.readilyapp.readable.storable;

/**
 * Created with love, by infm dated on 6/6/16.
 */

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;

/**
 * Defines abstraction for a reference to table of contents of a book.
 */
public interface AbstractTocReference extends Serializable {
  String getId();

  String getTitle();

  // It's a blocking action, use it carefully!
  String getPreview() throws IOException;

  String getCachedPreview();

  double getPercentile();

  // Specifies List implementation in order to pass between activities
  ArrayList<? extends AbstractTocReference> getChildren();
}
