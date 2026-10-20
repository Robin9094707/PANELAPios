package eu.simplexsmp.adminsuite;

import org.bukkit.inventory.ItemStack;

/** Compute a complete exchange on clones before reserving stock or changing a live inventory. */
final class ItemExchange {
    static ItemStack[] copy(ItemStack[] slots){ItemStack[] out=new ItemStack[slots.length];for(int i=0;i<slots.length;i++)out[i]=slots[i]==null?null:slots[i].clone();return out;}
    static ItemStack[] plan(ItemStack[] slots,ItemStack currency,int amount,ItemStack reward){
        if(amount<=0||amount>2304||currency==null||currency.getType()==org.bukkit.Material.AIR)throw new IllegalArgumentException("Invalid item payment");
        ItemStack[] out=copy(slots);int remaining=amount;
        for(int i=0;i<out.length&&remaining>0;i++){ItemStack item=out[i];if(item==null||!item.isSimilar(currency))continue;int take=Math.min(remaining,item.getAmount());remaining-=take;if(take==item.getAmount())out[i]=null;else item.setAmount(item.getAmount()-take);}
        if(remaining!=0)throw new IllegalArgumentException("Not enough matching payment items");
        return add(out,reward);
    }
    static ItemStack[] add(ItemStack[] slots,ItemStack reward){
        ItemStack[] out=copy(slots);int remaining=reward.getAmount(),max=reward.getMaxStackSize();if(remaining<=0||max<=0)throw new IllegalArgumentException("Invalid bundle");
        for(ItemStack item:out)if(item!=null&&item.isSimilar(reward)){int space=Math.max(0,Math.min(item.getMaxStackSize(),max)-item.getAmount());int put=Math.min(space,remaining);item.setAmount(item.getAmount()+put);remaining-=put;if(remaining==0)return out;}
        for(int i=0;i<out.length&&remaining>0;i++)if(out[i]==null||out[i].getType()==org.bukkit.Material.AIR){out[i]=reward.clone();int put=Math.min(max,remaining);out[i].setAmount(put);remaining-=put;}
        if(remaining!=0)throw new IllegalArgumentException("Insufficient inventory space");return out;
    }
}
