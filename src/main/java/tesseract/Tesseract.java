package tesseract;

import net.minecraft.item.ItemStack;
import net.minecraft.util.RegistryKey;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.server.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;
import tesseract.api.GraphWrapper;
import tesseract.api.capability.TesseractGTCapability;
import tesseract.api.fe.FEController;
import tesseract.api.fe.IFECable;
import tesseract.api.fe.IFENode;
import tesseract.api.fluid.IFluidNode;
import tesseract.api.fluid.IFluidPipe;
import tesseract.api.gt.IGTCable;
import tesseract.api.gt.IGTNode;
import tesseract.api.item.IItemNode;
import tesseract.api.item.IItemPipe;
import tesseract.api.item.ItemController;
import tesseract.controller.Energy;
import tesseract.controller.Fluid;

@Mod(Tesseract.API_ID)
//@Mod.EventBusSubscriber(bus=Mod.EventBusSubscriber.Bus.MOD)
public class Tesseract {

	public static final String API_ID = "tesseract";
	public static final String API_NAME = "Tesseract API";
	public static final String VERSION = "0.0.1";
	public static final String DEPENDS = "";

	public static GraphWrapper<Integer,IFECable, IFENode> FE_ENERGY = new GraphWrapper<>(FEController::new);
	public static GraphWrapper<Long,IGTCable, IGTNode> GT_ENERGY = new GraphWrapper<>(Energy::new);
	public static GraphWrapper<FluidStack,IFluidPipe, IFluidNode> FLUID = new GraphWrapper<>(Fluid::new);
	public static GraphWrapper<ItemStack,IItemPipe, IItemNode> ITEM = new GraphWrapper<>(ItemController::new);

	private static boolean firstTick = false;

	public Tesseract() {
		MinecraftForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent
	public void commonSetup(FMLCommonSetupEvent event) {
		TesseractGTCapability.register();
	}

	@SubscribeEvent
	public void serverStoppedEvent(FMLServerStoppedEvent e) {
		firstTick = false;
	}

	@SubscribeEvent
	public void worldUnloadEvent(WorldEvent.Unload e) {
		FE_ENERGY.removeWorld((World)e.getWorld());
		GT_ENERGY.removeWorld((World)e.getWorld());
		ITEM.removeWorld((World)e.getWorld());
		FLUID.removeWorld((World)e.getWorld());
	}

    @SubscribeEvent
    public void onServerTick(TickEvent.WorldTickEvent event) {
		if (event.side.isClient()) return;
		World dim = event.world;
		if (!hadFirstTick()) {
			firstTick = true;
			GT_ENERGY.onFirstTick(dim);
			FE_ENERGY.onFirstTick(dim);
			FLUID.onFirstTick(dim);
			ITEM.onFirstTick(dim);
		}
		if (event.phase == TickEvent.Phase.START) {
            GT_ENERGY.tick(dim);
            FE_ENERGY.tick(dim);
            FLUID.tick(dim);
            ITEM.tick(dim);
        }
    }

	public static boolean hadFirstTick() {
		return firstTick;
	}
}
