import { supabase } from '../supabase';

/**
 * Proposals: what someone decided should happen, recorded before anything happens.
 *
 * Nothing in this module touches a device. Creating and approving are both records; carrying one
 * out is a separate act with its own permission, which is what keeps a distracted click from being
 * the same gesture as a decision.
 */

export type ProposalKind = 'BORRAR' | 'MOVER' | 'ARCHIVAR';
export type ProposalState = 'BORRADOR' | 'APROBADA' | 'EJECUTADA' | 'DESCARTADA';

export const KIND_LABEL: Record<ProposalKind, string> = {
  BORRAR: 'Borrar',
  MOVER: 'Mover',
  ARCHIVAR: 'Archivar',
};

export const STATE_LABEL: Record<ProposalState, string> = {
  BORRADOR: 'Borrador',
  APROBADA: 'Aprobada',
  EJECUTADA: 'Ejecutada',
  DESCARTADA: 'Descartada',
};

export type ProposalItem = {
  locationId: string;
  path: string;
  name: string;
  sizeBytes: number;
  isDirectory: boolean;
};

export type Proposal = {
  proposalId: string;
  deviceSlug: string;
  kind: ProposalKind;
  state: ProposalState;
  title: string;
  note: string | null;
  destPath: string | null;
  createdBy: string;
  createdAt: string;
  approvedBy: string | null;
  approvedAt: string | null;
  executedAt: string | null;
  itemCount: number;
  totalBytes: number;
};

export async function listProposals(deviceSlug?: string): Promise<Proposal[]> {
  const { data, error } = await supabase.rpc('fleet_proposals', {
    p_device_slug: deviceSlug ?? null,
  });
  if (error) throw new Error(error.message);
  return (data as Proposal[] | null) ?? [];
}

export async function proposalItems(proposalId: string): Promise<ProposalItem[]> {
  const { data, error } = await supabase.rpc('fleet_proposal_items', { p_proposal_id: proposalId });
  if (error) throw new Error(error.message);
  return (data as ProposalItem[] | null) ?? [];
}

export async function createProposal(input: {
  deviceSlug: string;
  kind: ProposalKind;
  title: string;
  items: ProposalItem[];
  destPath?: string;
  note?: string;
}): Promise<string> {
  const { data, error } = await supabase.rpc('fleet_propose', {
    p_device_slug: input.deviceSlug,
    p_kind: input.kind,
    p_title: input.title,
    p_items: input.items,
    p_dest_path: input.destPath ?? null,
    p_note: input.note ?? null,
  });
  if (error) throw new Error(error.message);
  return data as string;
}

export async function setProposalState(
  proposalId: string,
  state: 'BORRADOR' | 'APROBADA' | 'DESCARTADA',
): Promise<void> {
  const { error } = await supabase.rpc('fleet_set_proposal_state', {
    p_proposal_id: proposalId,
    p_state: state,
  });
  if (error) throw new Error(error.message);
}
