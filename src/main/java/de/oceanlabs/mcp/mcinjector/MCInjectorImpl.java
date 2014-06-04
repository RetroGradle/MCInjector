package de.oceanlabs.mcp.mcinjector;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import com.google.gson.*;

public class MCInjectorImpl
{
    private final static Logger log = Logger.getLogger("MCInjector");
    public final Map<String, JsonStruct> json = new HashMap<String, JsonStruct>();
    public final Properties mappings = new Properties();
    public final Properties outMappings = new Properties()
    {
        private static final long serialVersionUID = 4112578634029874840L;

        @Override
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public synchronized Enumeration keys()
        {
            Enumeration keysEnum = super.keys();
            Vector keyList = new Vector();
            while (keysEnum.hasMoreElements())
            {
                keyList.add(keysEnum.nextElement());
            }
            Collections.sort(keyList);
            return keyList.elements();
        }
    };
    public int initIndex = 0;
    public boolean generate = false;
    private boolean applyMarkers = false;
    public final InheratanceMap inheratance;

    public static void process(String inFile, String outFile, String mapFile, String logFile, String outMapFile, int index, String classJson, boolean applyMarkers)
        throws IOException
    {
        MCInjectorImpl mci = new MCInjectorImpl(index, outMapFile != null);
        mci.loadJson(classJson);
        mci.loadMap(mapFile);
        mci.applyMarkers = applyMarkers;

        mci.processJar(inFile, outFile);

        if (outMapFile != null)
        {
            mci.saveMap(outMapFile);
        }

        log.info("Processed " + inFile);
    }

    private MCInjectorImpl(int index, boolean generate)
    {
        this.initIndex = index;
        this.generate = generate;
        this.inheratance = generate ? new InheratanceMap() : null;
    }

    public void loadMap(String mapFile) throws IOException
    {
        Reader mapReader = null;
        try
        {
            mapReader = new FileReader(mapFile);
            this.mappings.load(mapReader);
            if (initIndex == 0)
            {
                initIndex = Integer.parseInt(mappings.getProperty("max_constructor_index", "1000"));
                log.info("Loaded Max Constructor Index: " + initIndex);
            }
            if (this.generate)
            {
                fixExceptions(this.mappings);
            }
        }
        catch (IOException e)
        {
            throw new IOException("Could not open map file: " + e.getMessage());
        }
        finally
        {
            if (mapReader != null)
            {
                try
                {
                    mapReader.close();
                }
                catch (IOException e)
                {
                    // ignore;
                }
            }
        }
    }

    private static final Gson GSON = new Gson();

    public void loadJson(String classJson) throws IOException
    {
        if (classJson == null) return;

        Reader reader = null;
        try
        {
            reader = new FileReader(classJson);
            json.clear();
            
            JsonObject object = (JsonObject)new JsonParser().parse(reader);
            for (Entry<String, JsonElement> entry : object.entrySet())
            {
                json.put(entry.getKey(), GSON.fromJson(entry.getValue(), JsonStruct.class));
            }
        }
        catch (IOException e)
        {
            throw new IOException("Could not open json file: " + e.getMessage());
        }
        finally
        {
            if (reader != null)
            {
                try
                {
                    reader.close();
                }
                catch (IOException e)
                {
                    // nom nom nom
                }
            }
        }
    }

    /*
    public static void main(String[] args) throws Exception
    {
        MCInjectorImpl impl = new MCInjectorImpl(0, false);
        impl.loadMap("Z:\\Clean\\1.7.9\\logs\\client_exc.log.exc");
        impl.fixExceptions(impl.mappings);
    }
    */

    private void fixExceptions(Properties props)
    {
        class Method
        {
            public String cls, desc;
            Method(String cls, String desc){
                this.cls = cls; this.desc = desc;
            }
        }
        class Line
        {
            public String name;
            public List<Method> classes = new ArrayList<Method>();
            public List<String> exceptions = new ArrayList<String>();
        }
        Map<String, Line> entries = new HashMap<String, Line>();
        for (Entry<Object, Object> e : props.entrySet())
        {
            String key = (String)e.getKey();
            String value = (String)e.getValue();
            if (value.indexOf('|') == -1) continue;
            String cls = key.split("\\.")[0];
            String name = key.split("\\.")[1];
            String desc = name.substring(name.indexOf('('));
            name = name.substring(0, name.indexOf('('));

            if (!name.startsWith("func_")) continue;

            Line line = entries.get(name);
            if (line == null)
            {
                line = new Line();
                line.name = name;
                entries.put(name, line);
            }
            line.classes.add(new Method(cls, desc));
            value = StringUtil.splitString(value, "|", -1).get(0);
            for (String exc : StringUtil.splitString(value, ",", -1))
            {
                if (!"".equals(exc) && !line.exceptions.contains(exc))
                    line.exceptions.add(exc);
            }
        }
        for (Entry<String, Line> e : entries.entrySet())
        {
            Line line = e.getValue();
            String excs = StringUtil.joinString(line.exceptions, ",", -1);
            for (Method m : line.classes)
            {
                String key = m.cls + "." + line.name + m.desc;
                List<String> old = StringUtil.splitString(props.getProperty(key), "|", -1);
                if (!excs.equals(old.get(0)))
                {
                    props.setProperty(key, excs + "|" + old.get(1));
                    MCInjectorImpl.log.info("Fixed Exception: " + key + ": " + old.get(0) + " -> " + excs);
                }
            }
        }
    }

    public void saveMap(String mapFile) throws IOException
    {
        Writer mapWriter = null;
        try
        {
            if (this.generate)
            {
                fixExceptions(this.mappings);
            }
            mapWriter = new FileWriter(mapFile);
            if (this.initIndex > 0)
            {
                this.outMappings.put("max_constructor_index", Integer.toString(initIndex));
                this.outMappings.store(mapWriter, "max index=" + this.initIndex);
            }
            else
            {
                this.outMappings.store(mapWriter, null);
            }
        }
        catch (IOException e)
        {
            throw new IOException("Could not write map file: " + e.getMessage());
        }
        finally
        {
            if (mapWriter != null)
            {
                try
                {
                    mapWriter.close();
                }
                catch (IOException e)
                {
                    // ignore;
                }
            }
        }
    }

    public String getMarker(String cls)
    {
        String marker = this.mappings.getProperty(cls);
        if (marker == null)
        {
            if (!this.generate) return null;
            marker = String.format("CL_%08d", this.initIndex++);
        }
        outMappings.put(cls, marker);
        return marker;
    }

    public List<String> getExceptions(String signature)
    {
        String curMap = this.mappings.getProperty(signature);
        if (curMap == null) return new ArrayList<String>();
        List<String> splitMap = StringUtil.splitString(curMap, "|", -1);
        if (splitMap.get(0).equals("")) return new ArrayList<String>();
        return  StringUtil.splitString(splitMap.get(0), ",");
    }
    
    public List<String> getParams(String signature)
    {
        String curMap = mappings.getProperty(signature);
        if (curMap == null) return new ArrayList<String>();
        List<String> split = StringUtil.splitString(curMap, "|", -1);
        if (split.size() <= 1 || split.get(1).equals("")) return new ArrayList<String>();
        return StringUtil.splitString(split.get(1), ",");
    }

    public void setExceptions(String signature, String excs)
    {
        String curMap = outMappings.getProperty(signature);   
        if (curMap == null) curMap = excs + "|"; 
        List<String> splitMap = StringUtil.splitString(curMap, "|", -1);
        outMappings.put(signature, excs + "|" + splitMap.get(1));
    }

    public void setParams(String signature, String params)
    {
        String curMap = outMappings.getProperty(signature);   
        if (curMap == null) curMap = "|" + params;
        List<String> splitMap = StringUtil.splitString(curMap, "|", -1);
        outMappings.put(signature, splitMap.get(0) + "|" + params);

        // Add to the input mappings so the generator will power the applier.
        curMap = mappings.getProperty(signature);   
        if (curMap == null) curMap = "|" + params;
        splitMap = StringUtil.splitString(curMap, "|", -1);
        mappings.put(signature, splitMap.get(0) + "|" + params);
    }

    public void setAccess(String signature, InheratanceMap.Access access)
    {
        outMappings.put(signature + "-Access", access.toString());
    }

    public InheratanceMap.Access getAccess(String signature)
    {
        String ent = mappings.getProperty(signature + "-Access");
        if (ent == null) return null;
        return InheratanceMap.Access.valueOf(ent);
    }

    public void processJar(String inFile, String outFile) throws IOException
    {
        if (this.inheratance != null)
        {
            gatherInheratance(inFile);
        }

        ZipInputStream inJar = null;
        ZipOutputStream outJar = null;

        try
        {
            try
            {
                inJar = new ZipInputStream(new BufferedInputStream(new FileInputStream(inFile)));
            }
            catch (FileNotFoundException e)
            {
                throw new FileNotFoundException("Could not open input file: " + e.getMessage());
            }

            try
            {
                OutputStream out = (outFile == null ? new ByteArrayOutputStream() : new FileOutputStream(outFile));
                outJar = new ZipOutputStream(new BufferedOutputStream(out));
            }
            catch (FileNotFoundException e)
            {
                throw new FileNotFoundException("Could not open output file: " + e.getMessage());
            }

            while (true)
            {
                ZipEntry entry = inJar.getNextEntry();

                if (entry == null)
                {
                    break;
                }

                if (entry.isDirectory())
                {
                    outJar.putNextEntry(entry);
                    continue;
                }

                byte[] data = new byte[4096];
                ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();

                int len;
                do
                {
                    len = inJar.read(data);
                    if (len > 0)
                    {
                        entryBuffer.write(data, 0, len);
                    }
                } while (len != -1);

                byte[] entryData = entryBuffer.toByteArray();

                String entryName = entry.getName();

                if (entryName.endsWith(".class") && entryName.startsWith("net/minecraft/") )
                {
                    MCInjectorImpl.log.log(Level.INFO, "Processing " + entryName);

                    entryData = this.processClass(entryData, outFile == null);

                    MCInjectorImpl.log.log(Level.INFO, "Processed " + entryBuffer.size() + " -> " + entryData.length);
                }
                else
                {
                    MCInjectorImpl.log.log(Level.INFO, "Copying " + entryName);
                }

                ZipEntry newEntry = new ZipEntry(entryName);
                outJar.putNextEntry(newEntry);
                outJar.write(entryData);
            }
        }
        finally
        {
            if (outJar != null)
            {
                try
                {
                    outJar.close();
                }
                catch (IOException e)
                {
                    // ignore
                }
            }

            if (inJar != null)
            {
                try
                {
                    inJar.close();
                }
                catch (IOException e)
                {
                    // ignore
                }
            }
        }
    }

    private void gatherInheratance(String inFile) throws IOException
    {
        ZipInputStream inJar = null;

        try
        {
            try
            {
                inJar = new ZipInputStream(new BufferedInputStream(new FileInputStream(inFile)));
            }
            catch (FileNotFoundException e)
            {
                throw new FileNotFoundException("Could not open input file: " + e.getMessage());
            }

            while (true)
            {
                ZipEntry entry = inJar.getNextEntry();

                if (entry == null) break;
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                
                byte[] data = new byte[4096];
                ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();

                int len;
                do
                {
                    len = inJar.read(data);
                    if (len > 0)
                    {
                        entryBuffer.write(data, 0, len);
                    }
                } while (len != -1);


                //MCInjectorImpl.log.log(Level.FINEST, "Processing " + entry.getName());
                inheratance.processClass(entryBuffer.toByteArray());
            }
        }
        finally
        {
            if (inJar != null)
            {
                try
                {
                    inJar.close();
                }
                catch (IOException e){}
            }
        }
    }

    public byte[] processClass(byte[] cls, boolean readOnly)
    {
        ClassReader cr = new ClassReader(cls);
        ClassNode cn = new ClassNode();
        
        ClassVisitor ca = cn;
        if (readOnly)
        {
            ca = new ReadMarkerClassAdaptor(ca, this);
        }
        else
        {
            ca = new ApplyMapClassAdapter(cn, this);       
            ca = new JsonAttributeClassAdaptor(ca, this);
    
            if (applyMarkers)
            {
                ca = new ApplyMarkerClassAdaptor(ca, this);
            }
    
            if (generate)
            {
                ca = new GenerateMapClassAdapter(ca, this);
            }

            ca = new AccessFixerClassAdaptor(ca, this);
        }
        ca = new AccessReaderClassAdaptor(ca, this);
        
        cr.accept(ca, 0);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(writer);
        return writer.toByteArray();
    }

}
