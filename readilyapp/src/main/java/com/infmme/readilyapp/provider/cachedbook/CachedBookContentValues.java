package com.infmme.readilyapp.provider.cachedbook;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import com.infmme.readilyapp.provider.base.AbstractContentValues;

/**
 * Content values wrapper for the {@code cached_book} table.
 */
public class CachedBookContentValues extends AbstractContentValues {
  @Override
  public Uri uri() {
    return CachedBookColumns.CONTENT_URI;
  }

  /**
   * Update row(s) using the values stored by this object and the given
   * selection.
   *
   * @param contentResolver The content resolver to use.
   * @param where           The selection to use (can be {@code null}).
   */
  public int update(ContentResolver contentResolver,
                    @Nullable CachedBookSelection where) {
    return contentResolver.update(uri(), values(),
                                  where == null ? null : where.sel(),
                                  where == null ? null : where.args());
  }

  /**
   * Update row(s) using the values stored by this object and the given
   * selection.
   *
   * @param contentResolver The content resolver to use.
   * @param where           The selection to use (can be {@code null}).
   */
  public int update(Context context, @Nullable CachedBookSelection where) {
    return context.getContentResolver()
                  .update(uri(), values(), where == null ? null : where.sel(),
                          where == null ? null : where.args());
  }

  /**
   * Last open time, joda standard datetime format.
   */
  public CachedBookContentValues putTimeOpened(@NonNull String value) {
    if (value == null)
      throw new IllegalArgumentException("timeOpened must not be null");
    mContentValues.put(CachedBookColumns.TIME_OPENED, value);
    return this;
  }


  /**
   * Optional link to epub_book.
   */
  public CachedBookContentValues putEpubBookId(@Nullable Long value) {
    mContentValues.put(CachedBookColumns.EPUB_BOOK_ID, value);
    return this;
  }

  public CachedBookContentValues putEpubBookIdNull() {
    mContentValues.putNull(CachedBookColumns.EPUB_BOOK_ID);
    return this;
  }

  /**
   * Optional link to fb2_book.
   */
  public CachedBookContentValues putFb2BookId(@Nullable Long value) {
    mContentValues.put(CachedBookColumns.FB2_BOOK_ID, value);
    return this;
  }

  public CachedBookContentValues putFb2BookIdNull() {
    mContentValues.putNull(CachedBookColumns.FB2_BOOK_ID);
    return this;
  }

  /**
   * Optional link to txt_book.
   */
  public CachedBookContentValues putTxtBookId(@Nullable Long value) {
    mContentValues.put(CachedBookColumns.TXT_BOOK_ID, value);
    return this;
  }

  public CachedBookContentValues putTxtBookIdNull() {
    mContentValues.putNull(CachedBookColumns.TXT_BOOK_ID);
    return this;
  }
}