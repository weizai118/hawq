package com.pivotal.pxf.plugins.hdfs;

import com.pivotal.pxf.api.OneRow;
import com.pivotal.pxf.api.ReadAccessor;
import com.pivotal.pxf.api.utilities.InputData;
import com.pivotal.pxf.api.utilities.Plugin;
import com.pivotal.pxf.plugins.hdfs.utilities.HdfsUtilities;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.*;

import java.io.IOException;
import java.util.LinkedList;
import java.util.ListIterator;

/*
 * Implementation of Accessor for accessing a splittable data source
 * - it means that HDFS will divide the file into splits based on an internal algorithm (by default, the 
 * the block size 64 MB is also the split size).  
 */
public abstract class HdfsSplittableDataAccessor extends Plugin implements ReadAccessor {
    protected Configuration conf = null;
    protected RecordReader<Object, Object> reader = null;
    protected FileInputFormat<?, ?> fformat = null;
    protected ListIterator<InputSplit> iter = null;
    protected JobConf jobConf = null;
    protected Object key, data;

    /*
     * C'tor
     */
    public HdfsSplittableDataAccessor(InputData input, FileInputFormat<?, ?> inFormat) throws Exception {
        super(input);
        fformat = inFormat;

        // 1. Load Hadoop configuration defined in $HADOOP_HOME/conf/*.xml files
        conf = new Configuration();

        // 2. variable required for the splits iteration logic
        jobConf = new JobConf(conf, HdfsSplittableDataAccessor.class);
    }

    /*
     * openForRead
     * Fetches the first split (relevant to this segment) in the file, using
     * the splittable API - InputFormat
     */
    public boolean openForRead() throws Exception {
        LinkedList<InputSplit> segSplits = new LinkedList<InputSplit>();
        // add split from inputData.getFragmentMetadata
        FileSplit fileSplit = HdfsUtilities.parseFragmentMetadata(inputData);
        segSplits.add(fileSplit);

        // Initialize record reader based on current split
        iter = segSplits.listIterator(0);
        return getNextSplit();
    }

    /*
     * Specialized accessors will override this method and implement their own recordReader
     */
    abstract protected Object getReader(JobConf jobConf, InputSplit split) throws IOException;

    /*
     * getNextSplit
     * Sets the current split and initializes a RecordReader who feeds from the split
     */
    @SuppressWarnings(value = "unchecked")
    protected boolean getNextSplit() throws IOException {
        if (!iter.hasNext()) {
            return false;
        }

        InputSplit currSplit = iter.next();
        reader = (RecordReader<Object, Object>) getReader(jobConf, currSplit);
        key = reader.createKey();
        data = reader.createValue();
        return true;
    }

    /*
     * readNextObject
     * Fetches one record from the  file. The record is returned as a Java object.
     */
    @Override
    public OneRow readNextObject() throws IOException {
        if (!reader.next(key, data)) // if there is one more record in the current split
        {
            if (getNextSplit()) // the current split is exhausted. try to move to the next split.
            {
                if (!reader.next(key, data)) // read the first record of the new split
                {
                    return null; // make sure we return nulls
                }
            } else {
                return null; // make sure we return nulls
            }
        }

		/*
         * if neither condition was met, it means we already read all the records in all the splits, and
		 * in this call record variable was not set, so we return null and thus we are signaling end of 
		 * records sequence
		*/
        return new OneRow(key, data);
    }

    /*
     * closeForRead
     * When user finished reading the file, it closes the RecordReader
     */
    public void closeForRead() throws Exception {
        if (reader != null) {
            reader.close();
        }
    }

    @Override
    public boolean isThreadSafe() {
        return HdfsUtilities.isThreadSafe(inputData);
    }

}
