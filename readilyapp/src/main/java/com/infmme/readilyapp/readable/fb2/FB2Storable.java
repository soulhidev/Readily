package com.infmme.readilyapp.readable.fb2;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import com.daimajia.androidanimations.library.BuildConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.infmme.readilyapp.provider.cachedbook.CachedBookColumns;
import com.infmme.readilyapp.provider.cachedbook.CachedBookContentValues;
import com.infmme.readilyapp.provider.cachedbook.CachedBookCursor;
import com.infmme.readilyapp.provider.cachedbook.CachedBookSelection;
import com.infmme.readilyapp.provider.fb2book.Fb2BookColumns;
import com.infmme.readilyapp.provider.fb2book.Fb2BookContentValues;
import com.infmme.readilyapp.provider.fb2book.Fb2BookCursor;
import com.infmme.readilyapp.provider.fb2book.Fb2BookSelection;
import com.infmme.readilyapp.readable.Readable;
import com.infmme.readilyapp.readable.interfaces.*;
import com.infmme.readilyapp.reader.Reader;
import com.infmme.readilyapp.xmlparser.FB2Tags;
import com.infmme.readilyapp.xmlparser.XMLEvent;
import com.infmme.readilyapp.xmlparser.XMLEventType;
import com.infmme.readilyapp.xmlparser.XMLParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;

import static com.infmme.readilyapp.readable.Utils.guessCharset;

/**
 * Created with love, by infm dated on 6/14/16.
 * <p>
 * Class to handle .fb2
 */
public class FB2Storable implements Storable, Chunked, Unprocessed,
    Structured {

  private static final int BUFFER_SIZE = 4096;

  private String mPath;
  private long mFileSize;

  /**
   * Creation time in order to keep track of db records.
   * Has joda LocalDateTime format.
   */
  private String mTimeCreated;

  private String mTitle = null;
  private String mCoverImageHref = null;
  private String mCoverImageUri = null;

  private XMLParser mParser;
  private XMLEvent mCurrentEvent;
  private XMLEventType mCurrentEventType;

  private Deque<ChunkInfo> mLoadedChunks = new ArrayDeque<>();
  private List<? extends AbstractTocReference> mTableOfContents = null;

  private transient String mCurrentPartId;
  private long mCurrentBytePosition = 0;
  private transient long mLastBytePosition = 0;

  private int mCurrentTextPosition;
  private double mChunkPercentile = .0;

  private boolean mProcessed;

  // Again, can be leaking.
  private transient Context mContext = null;

  public FB2Storable(Context context) {
    mContext = context;
  }

  public FB2Storable(Context context, String timeCreated) {
    this.mTimeCreated = timeCreated;
    this.mContext = context;
  }

  @Override
  protected void finalize() throws Throwable {
    super.finalize();
    // Closes inner input stream.
    mParser.close();
  }

  @Override
  public Reading readNext() throws IOException {
    // Checks if we're reading for the first time.
    if (mParser.getPosition() == 0 && mCurrentBytePosition != 0) {
      mParser.skip(mCurrentBytePosition);
    }
    mCurrentBytePosition = mLastBytePosition;

    StringBuilder text = new StringBuilder();
    if (mCurrentEvent == null) {
      mCurrentEvent = mParser.next();
      mCurrentEventType = mCurrentEvent.getType();
      mLastBytePosition = mParser.getPosition();
    }

    boolean insideBookTitle = false;
    boolean insideCoverPage = false;
    // Stack needed to keep track of nested sections entered.
    Stack<String> sectionIdStack = new Stack<>();

    // Reads file section by section until reaches end of the file or text
    // grows bigger, than buffer size.
    while (mCurrentEventType != XMLEventType.DOCUMENT_CLOSE &&
        text.length() < BUFFER_SIZE) {
      if (mCurrentEvent.enteringBookTitle()) {
        insideBookTitle = true;
      }
      if (mCurrentEvent.exitingBookTitle()) {
        insideBookTitle = false;
      }
      if (mCurrentEvent.enteringCoverPage()) {
        insideCoverPage = true;
      }
      if (mCurrentEvent.exitingCoverPage()) {
        insideCoverPage = false;
      }
      if (mCurrentEvent.enteringSection()) {
        if (BuildConfig.DEBUG) {
          Log.d(getClass().getName(),
                "Entering section on " + String.valueOf(mParser.getPosition()));
        }
        mCurrentPartId = "section" + mParser.getPosition();
        sectionIdStack.add(mCurrentPartId);
      }
      if (mCurrentEvent.exitingSection()) {
        if (BuildConfig.DEBUG) {
          Log.d(getClass().getName(),
                String.format("Exiting section %s on %d", mTitle,
                              mParser.getPosition()));
        }
        // Checks if we're in a nested section.
        if (!sectionIdStack.isEmpty()) {
          sectionIdStack.pop();
        }
        // If we should use parent section as a current one.
        if (!sectionIdStack.isEmpty()) {
          mCurrentPartId = sectionIdStack.peek();
        }
      }
      if (mCurrentEventType == XMLEventType.CONTENT) {
        String contentType = mCurrentEvent.getContentType();
        String content = mCurrentEvent.getContent();
        if (contentType != null && !TextUtils.isEmpty(contentType)) {
          // Appends plain text to a text.
          // TODO: Check out other possible tags.
          if (contentType.equals(FB2Tags.PLAIN_TEXT)) {
            text.append(content).append(" ");
          } else if (contentType.equals(FB2Tags.BOOK_TITLE)) {
            if (insideBookTitle && mTitle == null) {
              mTitle = content;
            }
          }
        }
      } else if (insideCoverPage && mCurrentEvent.isImageTag()) {
        HashMap<String, String> attrs = mCurrentEvent.getTagAttributes();
        if (attrs != null) {
          for (HashMap.Entry<String, String> kv : attrs.entrySet()) {
            if (kv.getKey().contains("href")) {
              mCoverImageHref = kv.getValue();
              break;
            }
          }
        }
      }

      mLastBytePosition = mParser.getPosition();

      mCurrentEvent = mParser.next();
      mCurrentEventType = mCurrentEvent.getType();
    }

    Readable readable = new Readable();
    readable.setText(text.toString());
    readable.setPosition(0);

    mLoadedChunks.addLast(
        new ChunkInfo(mCurrentPartId, mCurrentBytePosition));

    return readable;
  }

  @Override
  public boolean hasNextReading() {
    return mCurrentEventType != XMLEventType.DOCUMENT_CLOSE;
  }

  @Override
  public void skipLast() {
    mLoadedChunks.removeLast();
  }

  // TODO: Transfer this method to model class.
  @Override
  public boolean isStoredInDb() {
    CachedBookSelection where = new CachedBookSelection();
    where.path(mPath);
    Cursor c = mContext.getContentResolver()
                       .query(CachedBookColumns.CONTENT_URI,
                              new String[] { CachedBookColumns._ID },
                              where.sel(), where.args(), null);
    boolean result = true;
    if (c != null) {
      CachedBookCursor book = new CachedBookCursor(c);
      if (book.getCount() < 1) {
        result = false;
      }
      book.close();
    } else {
      result = false;
    }
    return result;
  }

  @Override
  public void readFromDb() {
    if (isStoredInDb()) {
      CachedBookSelection where = new CachedBookSelection();
      where.path(mPath);
      Cursor c = mContext.getContentResolver()
                         .query(CachedBookColumns.CONTENT_URI,
                                Fb2BookColumns.ALL_COLUMNS,
                                where.sel(), where.args(), null);
      if (c != null && c.moveToFirst()) {
        if (c.moveToFirst()) {
          Fb2BookCursor book = new Fb2BookCursor(c);
          mCurrentPartId = book.getCurrentPartId();
          mCurrentTextPosition = book.getTextPosition();
          mCurrentBytePosition = book.getBytePosition();
          mLastBytePosition = mCurrentBytePosition;
          book.close();
        } else {
          c.close();
        }
      } else {
        throw new RuntimeException("Unexpected cursor fail.");
      }
    } else {
      throw new IllegalStateException("Not stored in a db yet!");
    }
  }

  @Override
  public void prepareForStoring(Reader reader) {
    if (mLoadedChunks != null && !mLoadedChunks.isEmpty()) {
      mCurrentBytePosition = mLoadedChunks.getFirst().mBytePosition;
      mChunkPercentile = reader.getPercentile();
      setCurrentPosition(reader.getPosition());
    }
  }

  @Override
  public void storeToDb() {
    checkSectionByteIntegrity();
    double percent = calcPercentile();

    CachedBookContentValues values = new CachedBookContentValues();

    Fb2BookContentValues fb2Values = new Fb2BookContentValues();
    fb2Values.putBytePosition((int) mCurrentBytePosition);
    fb2Values.putTextPosition(mCurrentTextPosition);
    fb2Values.putCurrentPartId(mCurrentPartId);

    if (isStoredInDb()) {
      CachedBookSelection cachedWhere = new CachedBookSelection();
      cachedWhere.path(mPath);
      if (percent >= 0 && percent <= 1) {
        values.putPercentile(calcPercentile());
        values.update(mContext, cachedWhere);
      }
      Fb2BookSelection fb2Where = new Fb2BookSelection();
      fb2Where.id(getFkFb2BookId());
      fb2Values.update(mContext, fb2Where);
      values.update(mContext, cachedWhere);
    } else {
      if (percent >= 0 && percent <= 1) {
        values.putPercentile(calcPercentile());
      } else {
        values.putPercentile(0);
      }
      values.putTimeOpened(mTimeCreated);
      values.putPath(mPath);
      values.putTitle(mTitle);
      values.putCoverImageUri(mCoverImageUri);

      Uri uri = fb2Values.insert(mContext.getContentResolver());
      long fb2Id = Long.parseLong(uri.getLastPathSegment());
      values.putFb2BookId(fb2Id);
      values.insert(mContext.getContentResolver());
    }
  }

  /**
   * Attempts to fix integrity issues between byte offset and section id.
   */
  private void checkSectionByteIntegrity() {
    if (mTableOfContents == null && isTocCached(mContext)) {
      try {
        mTableOfContents = readSavedToc(mContext);
      } catch (IOException e) {
        e.printStackTrace();
        return;
      }
    }
    if (mTableOfContents != null) {
      for (int i = 0; i < mTableOfContents.size(); i++) {
        FB2Part part = (FB2Part) mTableOfContents.get(i);
        if (part.getId().equals(mCurrentPartId)) {
          if (mCurrentBytePosition < part.getStreamByteStartLocation() ||
              mCurrentBytePosition > part.getStreamByteEndLocation()) {
            // Integrity is violated.
            mCurrentBytePosition = part.getStreamByteStartLocation();
          }
        }
      }
    }
  }

  @Override
  public void storeToFile() {
    throw new IllegalStateException(
        "You can't store FB2Storable to a filesystem.");
  }

  @Override
  public Storable readFromFile() throws IOException {
    File file = new File(mPath);
    mFileSize = file.length();
    FileInputStream encodingHelper = new FileInputStream(file);
    String encoding = guessCharset(encodingHelper);
    encodingHelper.close();

    FileInputStream inputStream = new FileInputStream(file);

    mParser = new XMLParser();
    mParser.setInput(inputStream, encoding);
    return this;
  }

  @Override
  public String getPath() {
    return mPath;
  }

  @Override
  public void setPath(String path) {
    mPath = path;
  }

  @Override
  public void onReaderNext() {
    mLoadedChunks.removeFirst();
  }

  @Override
  public List<? extends AbstractTocReference> getTableOfContents() {
    if (mTableOfContents == null && mProcessed) {
      try {
        if (isTocCached(mContext)) {
          mTableOfContents = readSavedToc(mContext);
        } else {
          ArrayList<FB2Part> toc = new ArrayList<>();
          Stack<FB2Part> stack = new Stack<>();
          FB2Part currentPart = null;
          // Gets initial data for an algorithm.
          XMLEvent event = mParser.next();
          XMLEventType eventType = event.getType();
          boolean insideTitle = false;

          while (eventType != XMLEventType.DOCUMENT_CLOSE) {
            if (event.enteringSection()) {
              // Checks if we're not in the section
              if (currentPart == null) {
                currentPart = new FB2Part(mParser.getPosition(), mPath);
              } else {
                FB2Part childPart = new FB2Part(mParser.getPosition(), mPath);
                currentPart.addChild(childPart);
                stack.add(currentPart);
                currentPart = childPart;
              }
            }
            if (event.exitingSection()) {
              if (currentPart == null) {
                throw new IllegalStateException("Can't exit non-existing part");
              }
              // This is guaranteed to be unique
              currentPart.setId("section" + String.valueOf(
                  currentPart.getStreamByteStartLocation()));
              currentPart.setStreamByteEndLocation(mParser.getPosition());
              if (stack.isEmpty()) {
                toc.add(currentPart);
                currentPart = null;
              } else {
                currentPart = stack.pop();
              }
            }
            if (event.enteringTitle()) {
              insideTitle = true;
            }
            if (event.exitingTitle()) {
              insideTitle = false;
            }

            // Checks if we're inside tag
            if (eventType == XMLEventType.CONTENT) {
              if (insideTitle && currentPart != null) {
                // Appends title to an existent one.
                currentPart.setTitle(
                    currentPart.getTitle() + " " + event.getContent());
              }
            }
            event = mParser.next();
            eventType = event.getType();
          }
          mTableOfContents = toc;
          saveToc(mContext, toc);
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    return mTableOfContents;
  }

  @Override
  public String getCurrentId() {
    return mCurrentPartId;
  }

  @Override
  public void setCurrentTocReference(AbstractTocReference tocReference) {
    FB2Part fb2Part = (FB2Part) tocReference;
    mCurrentPartId = fb2Part.getId();
    mCurrentBytePosition = fb2Part.getStreamByteStartLocation();
  }

  @Override
  public int getCurrentPosition() {
    return mCurrentTextPosition;
  }

  @Override
  public void setCurrentPosition(int position) {
    mCurrentTextPosition = position;
  }

  @Override
  public boolean isProcessed() {
    return mProcessed;
  }

  @Override
  public void setProcessed(boolean processed) {
    mProcessed = processed;
  }

  @Override
  public void process() {
    try {
      readFromFile();
      if (isStoredInDb()) {
        readFromDb();
      } else {
        mCurrentTextPosition = 0;
      }

      mProcessed = true;
    } catch (IOException e) {
      e.printStackTrace();
      mProcessed = false;
    }
  }

  public boolean isTocCached(Context c) {
    return getCachedTocFile(c).exists();
  }

  public void saveToc(Context c, ArrayList<FB2Part> toc)
      throws IOException {
    FileOutputStream fos = new FileOutputStream(getCachedTocFile(c));
    Gson gson = new Gson();
    String json = gson.toJson(toc);
    fos.write(json.getBytes());
    fos.close();
  }

  public ArrayList<FB2Part> readSavedToc(Context c)
      throws IOException {
    FileInputStream fis = new FileInputStream(getCachedTocFile(c));

    byte[] buffer = new byte[BUFFER_SIZE];
    StringBuilder input = new StringBuilder();
    long bytesRead;
    do {
      bytesRead = fis.read(buffer);
      if (bytesRead != BUFFER_SIZE) {
        byte[] buffer0 = new byte[(int) bytesRead];
        System.arraycopy(buffer, 0, buffer0, 0, (int) bytesRead);
        input.append(new String(buffer0));
      } else {
        input.append(new String(buffer));
      }
    } while (bytesRead == BUFFER_SIZE);

    String json = input.toString();
    Gson gson = new Gson();
    Type listType = new TypeToken<ArrayList<FB2Part>>() {}.getType();

    // Path is needed in order to get preview and, basically, perform any
    // operation with fb2 itself.
    ArrayList<FB2Part> toc = gson.fromJson(json, listType);
    for (FB2Part part : toc) {
      part.setPath(mPath);
    }
    return toc;
  }

  private File getCachedTocFile(Context c) {
    return new File(c.getCacheDir(),
                    mPath.substring(mPath.lastIndexOf('/')) + "_TOC.json");
  }

  /**
   * Uses uniqueness of a path to get fb2_book_id from a cached_book table.
   *
   * @return fb2_book_id for an mPath.
   */
  private Long getFkFb2BookId() {
    Long id = null;

    CachedBookSelection cachedWhere = new CachedBookSelection();
    cachedWhere.path(mPath);
    CachedBookCursor cachedBookCursor =
        new CachedBookCursor(mContext.getContentResolver().query(
            CachedBookColumns.CONTENT_URI,
            new String[] { CachedBookColumns.FB2_BOOK_ID },
            cachedWhere.sel(), cachedWhere.args(), null));
    if (cachedBookCursor.moveToFirst()) {
      id = cachedBookCursor.getFb2BookId();
    }
    cachedBookCursor.close();
    return id;
  }

  private double calcPercentile() {
    long nextBytePosition;
    if (mLoadedChunks.isEmpty()) {
      nextBytePosition = mFileSize;
    } else {
      nextBytePosition = mLoadedChunks.getFirst().mBytePosition;
    }
    return (double) mCurrentBytePosition / mFileSize + mChunkPercentile *
        (nextBytePosition - mCurrentBytePosition) / mFileSize;
  }

  private class ChunkInfo {
    String mSectionId;
    long mBytePosition;

    public ChunkInfo(String sectionTitle, long bytePosition) {
      this.mSectionId = sectionTitle;
      this.mBytePosition = bytePosition;
    }
  }
}