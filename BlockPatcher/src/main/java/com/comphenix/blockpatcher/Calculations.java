package com.comphenix.blockpatcher;

import java.util.concurrent.TimeUnit;

import net.minecraft.server.v1_8_R1.Block;
import net.minecraft.server.v1_8_R1.ChunkMap;
import net.minecraft.server.v1_8_R1.IBlockData;
import net.minecraft.server.v1_8_R1.MultiBlockChangeInfo;
import net.minecraft.server.v1_8_R1.PacketPlayOutMultiBlockChange;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.comphenix.blockpatcher.lookup.ConversionLookup;
import com.comphenix.blockpatcher.lookup.SegmentLookup;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.FieldAccessException;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.ChunkCoordIntPair;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.google.common.base.Stopwatch;

/**
 * Used to translate block IDs.
 * 
 * @author Kristian
 */
class Calculations {
	// Used to pass around detailed information about chunks
	private static class ChunkInfo {
	    public int chunkX;
	    public int chunkZ;
	    public int chunkMask;
	    //public int extraMask;
	    public int chunkSectionNumber;
	    public byte[] data;
	    public Player player;
	    public int startIndex;
	    public int blockSize;
	}
	
	// Useful Minecraft constants
	private static final int CHUNK_SEGMENTS = 16;
	
	// Used to get a chunk's specific lookup table
	private EventScheduler scheduler;
	private ConversionCache cache;
	
	public Calculations(ConversionCache cache, EventScheduler scheduler) {
		this.cache = cache;
		this.scheduler = scheduler;
	}
	
	public boolean isImportantChunkBulk(PacketContainer packet, Player player) throws FieldAccessException {
    	StructureModifier<int[]> intArrays = packet.getSpecificModifier(int[].class);
        int[] x = intArrays.read(0); 
        int[] z = intArrays.read(1); 
        
        for (int i = 0; i < x.length; i++) {
            if (Math.abs(x[i] - (((int) player.getLocation().getX()) >> 4)) == 0 && 
            	Math.abs(z[i] - (((int) player.getLocation().getZ())) >> 4) == 0) {
                return true;
            }
        }
        return false;
	}
	
	public boolean isImportantChunk(PacketContainer packet, Player player) throws FieldAccessException {
		StructureModifier<Integer> ints = packet.getSpecificModifier(int.class);
		int x = ints.read(0); 	
        int y = ints.read(1);
        
        if (Math.abs(x - (((int) player.getLocation().getX()) >> 4)) == 0 && 
        	Math.abs(y - (((int) player.getLocation().getZ())) >> 4) == 0) {
        	return true;
        }
        return false;
	}
	
	public void translateMapChunkBulk(PacketContainer packet, Player player) throws FieldAccessException {
		StructureModifier<int[]> intArrays = packet.getSpecificModifier(int[].class);
    	
        int[] x = intArrays.read(0); // a
        int[] z = intArrays.read(1); // b
        
		ChunkMap[] chunkMaps = packet.getSpecificModifier(ChunkMap[].class).read(0);
		
		ChunkInfo[] infos = new ChunkInfo[x.length];
		
		for (int chunkNum = 0; chunkNum < infos.length; chunkNum++) {
			// Create an info objects
            ChunkInfo info = new ChunkInfo();
            infos[chunkNum] = info;
            info.player = player;
            info.chunkX = x[chunkNum];
            info.chunkZ = z[chunkNum];
            
            ChunkMap chunkMap = chunkMaps[chunkNum];

            info.chunkMask = chunkMap.b;
            info.data = chunkMap.a;
            
            translateChunkInfoAndObfuscate(info, info.data);
		}
		
    }
    
    public void translateMapChunk(PacketContainer packet, Player player) throws FieldAccessException  {
    	StructureModifier<Integer> ints = packet.getSpecificModifier(int.class);
    	
    	ChunkMap chunkMap = packet.getSpecificModifier(ChunkMap.class).read(0);
        
        // Create an info objects
        ChunkInfo info = new ChunkInfo();
        info.player = player;
        info.chunkX = ints.read(0); 	// packet.a;
        info.chunkZ = ints.read(1); 	// packet.b;

        info.chunkMask = chunkMap.b;
        info.data = chunkMap.a;
        
        info.startIndex = 0;
        
        translateChunkInfoAndObfuscate(info, info.data);
    }
        
    public void translateBlockChange(PacketContainer packet, Player player) throws FieldAccessException {
    	BlockPosition position = packet.getBlockPositionModifier().read(0);
    	IBlockData block = packet.getSpecificModifier(IBlockData.class).read(0);
    	
    	ConversionLookup lookup = cache.loadCacheOrDefault(player, position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4);
    	
    	int blockID = Block.getId(block.getBlock());
		int data = block.getBlock().toLegacyData(block);
		
		int newBlockID = lookup.getBlockLookup(blockID);
		int newData = lookup.getDataLookup(blockID, data);
    	
    	packet.getSpecificModifier(IBlockData.class).write(0, Block.getById(newBlockID).fromLegacyData(newData));
    }
        
    public void translateMultiBlockChange(PacketContainer packet, Player player) throws FieldAccessException {
    	ChunkCoordIntPair coord = packet.getChunkCoordIntPairs().read(0);
    	
    	MultiBlockChangeInfo[] changeInfos = packet.getSpecificModifier(MultiBlockChangeInfo[].class).read(0);
    	
    	// Get the correct table
    	SegmentLookup lookup = cache.loadCacheOrDefault(player, coord.getChunkX(), coord.getChunkZ());
    	
    	for (int i = 0; i < changeInfos.length; i ++) {
    		MultiBlockChangeInfo changeInfo = changeInfos[i];
    		IBlockData block = changeInfo.c();
    		short d = changeInfo.b();
    		
    		int chunkY = (d & 0x00FF) >> 4;
    		
	    	int blockID = Block.getId(block.getBlock());
			int data = block.getBlock().toLegacyData(block);
			
			int newBlockID = lookup.getBlockLookup(blockID, chunkY);
			int newData = lookup.getDataLookup(blockID, data, chunkY);
	    	
			changeInfos[i] = new MultiBlockChangeInfo((PacketPlayOutMultiBlockChange) packet.getHandle(), changeInfo.b(), Block.getById(newBlockID).fromLegacyData(newData));
    	}
    }
    
    public void translateFallingObject(PacketContainer packet, Player player) throws FieldAccessException {
    	StructureModifier<Integer> ints = packet.getSpecificModifier(int.class);

    	int type = ints.read(9);
    	int rawData = ints.read(10);
    	
    	int blockID = ((((rawData) & 0xFF)) | ((rawData & 0xF00) << 4));
    	
        int data = rawData >> 12 & 15;
    
    	// Falling object (only block ID)
    	if (type == 70) {
        	int x = ints.read(1) / 32;
        	int y = ints.read(2) / 32;
        	int z = ints.read(3) / 32;
    		
        	// Get the correct table
        	ConversionLookup lookup = cache.loadCacheOrDefault(player, x >> 4, y >> 4, z >> 4);
    		
    		int newBlockID = lookup.getBlockLookup(blockID);
    		int newData = lookup.getDataLookup(blockID, data);
    		
            ints.write(10, newBlockID + (newData << 12));
    	}
    }
    
    @SuppressWarnings("deprecation")
	public void translateDroppedItem(PacketContainer packet, Player player, EventScheduler scheduler) throws FieldAccessException {
    	
    	StructureModifier<Integer> ints = packet.getSpecificModifier(int.class);
    	
    	// Minecraft 1.3.2 or lower
    	if (ints.size() > 4) {

        	int itemsID = ints.read(4);
        	int count = ints.read(5);
        	int data = ints.read(6);
        	
        	ItemStack stack = new ItemStack(itemsID, count, (short) data);
        	scheduler.computeItemConversion(new ItemStack[] { stack }, player, false);
        	
        	// Make sure it has changed
        	if (stack.getTypeId() != itemsID || stack.getAmount() != count || stack.getDurability() != data) {
        		ints.write(4, stack.getTypeId());
        		ints.write(5, stack.getAmount());
        		ints.write(6, (int) stack.getDurability());
        	}
        	
    	// Minecraft 1.4.2
    	} else {
    		StructureModifier<ItemStack> stacks = packet.getItemModifier();
    		
    		// Very simple
    		if (stacks.size() > 0)
    			scheduler.computeItemConversion(new ItemStack[] { stacks.read(0) }, player, false);
    		else
    			throw new IllegalStateException("Unrecognized packet structure.");
    	}
    }
    
	public void translateDroppedItemMetadata(PacketContainer packet, Player player, EventScheduler scheduler) {
		Entity entity = packet.getEntityModifier(player.getWorld()).read(0);
		
		if (entity instanceof Item) {
			// Great. Get the item from the DataWatcher
	        WrappedDataWatcher original = new WrappedDataWatcher(
	                packet.getWatchableCollectionModifier().read(0)
	        );
	        
	        // Clone it
	        WrappedDataWatcher watcher = original.deepClone();
	        
	        // Allow mods to convert it and write back the result
	        scheduler.computeItemConversion(new ItemStack[] { watcher.getItemStack(10) }, player, false);
	        packet.getWatchableCollectionModifier().write(0, watcher.getWatchableObjects());
		}
	}

    private boolean isChunkLoaded(World world, int x, int z) {
        return world.isChunkLoaded(x, z);
    }
    
    private void translateChunkInfoAndObfuscate(ChunkInfo info, byte[] returnData) {
        // Compute chunk number
        for (int i = 0; i < CHUNK_SEGMENTS; i++) {
            if ((info.chunkMask & (1 << i)) > 0) {
                info.chunkSectionNumber++;
            }
        }
        
        info.blockSize = 4096 * info.chunkSectionNumber;
        
        if (info.startIndex + info.blockSize > info.data.length) {
            return;
        }

        // Make sure the chunk is loaded 
        if (isChunkLoaded(info.player.getWorld(), info.chunkX, info.chunkZ)) {
        	// Invoke the event
        	SegmentLookup baseLookup = cache.getDefaultLookupTable();
        	SegmentLookup lookup = scheduler.getChunkConversion(baseLookup, info.player, info.chunkX, info.chunkZ);
        	
        	// Save the result to the cache, if it's not the default
        	if (!baseLookup.equals(lookup)) {
        		cache.saveCache(info.player, info.chunkX, info.chunkZ, lookup);
        	} else {
        		cache.saveCache(info.player, info.chunkX, info.chunkZ, null);
        	}
        	
            translate(lookup, info);
        }
    }
    
    private void translate(SegmentLookup lookup, ChunkInfo info) {
        // Loop over 16x16x16 chunks in the 16x256x16 column
        int idIndexModifier = 0;
        
        int idOffset = info.startIndex;
              
		//Stopwatch watch = Stopwatch.createStarted();
        
        for (int i = 0; i < 16; i++) {
            // If the bitmask indicates this chunk is sent
            if ((info.chunkMask & 1 << i) > 0) {
            	
            	ConversionLookup view = lookup.getSegmentView(i);
            	
                int relativeIDStart = idIndexModifier * 8192;
                int blockIndex = idOffset + relativeIDStart;
                
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++)  {
                        	int blockID = (((info.data[blockIndex + 1] & 0xFF) << 4) | ((info.data[blockIndex] & 0xFF) >>> 4));
                        	int data = info.data[blockIndex] & 15;
                        	
                            // Transform block
                        	int newBlockID = view.getBlockLookup(blockID);
                        	int newData = view.getDataLookup(blockID, data);
                        	
                            info.data[blockIndex] = (byte) ((newBlockID << 4) | newData);
                            info.data[blockIndex + 1] = (byte) (newBlockID >> 4);

                            blockIndex += 2;
                        }
                    }
                }
                
                idIndexModifier++;
            }
        }
        
        //watch.stop();
        //System.out.println(String.format("Processed x: %s, z: %s in %s ms.", 
        //			       info.chunkX, info.chunkZ, 
        //			       getMilliseconds(watch))
        //);
        
        // We're done
    }
    
	public static double getMilliseconds(Stopwatch watch) {
		return watch.elapsed(TimeUnit.NANOSECONDS) / 1000000.0;
	}
}
